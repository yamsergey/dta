package io.yamsergey.dta.daemon

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.connection.ForwardOps
import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.SidekickConnectionManager
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
import io.yamsergey.dta.daemon.layout.ForegroundDetector
import io.yamsergey.dta.daemon.layout.LayoutService
import io.yamsergey.dta.daemon.layout.UiAutomatorFallback
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.Screen
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side entry point of the channel feature. In this feature the daemon
 * ships as a library (no server process yet — see plan.md); this class is the
 * production wiring of discovery + forwards + the connection manager.
 */
class DtaDaemon(
    adb: AdbExec = AdbExec(),
    requestTimeout: Duration = 10.seconds,
) {
    private val runAdb: (List<String>) -> AdbExec.Result = { args -> adb.run(*args.toTypedArray()) }
    private val forwards = PortForwards(runAdb)

    init {
        // FR-011: forwards leaked by a previous run are cleaned at startup.
        // Never fatal — no adb/no devices just means nothing to sweep.
        runCatching { forwards.startupSweep() }
    }

    /** Re-runs the leaked-forward cleanup; returns how many were removed. */
    fun startupSweep(): Int = forwards.startupSweep()

    val discovery = SidekickDiscovery(runAdb)

    val connections = SidekickConnectionManager(
        forwardOps = ForwardOps(
            forward = { forwards.forward(it) },
            remove = { serial, port -> forwards.remove(serial, port) },
        ),
        connector = { key, port ->
            SidekickConnection.connect(
                uri = URI("ws://127.0.0.1:$port/"),
                expectedPackage = key.packageName,
                requestTimeout = requestTimeout,
            )
        },
        probe = { connection ->
            runCatching { connection.request("ping", null, 2.seconds) }.isSuccess
        },
    )

    private val foregroundDetector = ForegroundDetector(runAdb)
    private val fallback = UiAutomatorFallback(runAdb)

    /**
     * Capture the layout tree for [pkg] on [serial], applying the sidekick-vs-
     * fallback decision ladder with honest labeling (contract §4). [pkg] null
     * means "whatever is foreground" (fallback only).
     */
    suspend fun layout(serial: String, pkg: String?, filters: Map<String, Any?>? = null): LayoutCapture {
        val service = LayoutService(
            foregroundPackage = { foregroundDetector.foregroundPackage(it) },
            sidekickCapture = pkg?.let { p ->
                { params ->
                    val conn = connections.connect(ConnectionKey(serial, p))
                    val filterJson = (params["filters"] as? Map<*, *>)?.let { filtersToParams(it) }
                    val result = conn.request("layout_tree", filterJson)
                    DtaJson.decodeFromJsonElement(LayoutCapture.serializer(), result)
                }
            },
            uiautomatorCapture = { s ->
                val screen = screenOf(s)
                fallback.capture(s, screen.widthPx, screen.heightPx)
            },
            screenOf = { screenOf(it) },
        )
        return service.capture(serial, pkg, filters)
    }

    private fun screenOf(serial: String): Screen {
        // wm size / density via adb, best-effort.
        val size = runAdb(listOf("-s", serial, "shell", "wm", "size")).stdout
        val (w, h) = Regex("""(\d+)x(\d+)""").find(size)?.destructured
            ?.let { (a, b) -> a.toInt() to b.toInt() } ?: (0 to 0)
        val density = runAdb(listOf("-s", serial, "shell", "wm", "density")).stdout
            .let { Regex("""(\d+)""").find(it)?.value?.toFloat()?.div(160f) ?: 1f }
        return Screen(w, h, density)
    }

    private fun filtersToParams(filters: Map<*, *>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            filters.forEach { (k, v) ->
                if (v != null) put(k.toString(), kotlinx.serialization.json.JsonPrimitive(v.toString()))
            }
        }

    suspend fun shutdown() = connections.shutdown()
}
