package io.yamsergey.dta.daemon

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.connection.ForwardOps
import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.SidekickConnectionManager
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.daemon.discovery.Device
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
import io.yamsergey.dta.daemon.layout.ForegroundDetector
import io.yamsergey.dta.daemon.layout.LayoutService
import io.yamsergey.dta.daemon.layout.UiAutomatorFallback
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.NetworkScriptService
import io.yamsergey.dta.daemon.network.NetworkService
import io.yamsergey.dta.daemon.network.proto.BodyDecoding
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaRegistry
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.LayoutTreeParams
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.flow.Flow
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side entry point of the channel feature. In this feature the daemon
 * ships as a library (no server process yet — see plan.md); this class is the
 * production wiring of discovery + forwards + the connection manager.
 */
class DtaDaemon(
    private val runAdb: (List<String>) -> AdbExec.Result,
    /** Resolved adb binary, or null with [adbUnavailableReason] (feature 008: the daemon runs without adb). */
    private val adbPath: String?,
    requestTimeout: Duration = 10.seconds,
    /** Daemon state on the developer's machine: protobuf schemas and mappings (feature 006). */
    dataDir: java.io.File = java.io.File(System.getProperty("user.home"), ".dta"),
    private val adbUnavailableReason: String? = null,
) {
    constructor(
        adb: AdbExec = AdbExec(),
        requestTimeout: Duration = 10.seconds,
        dataDir: java.io.File = java.io.File(System.getProperty("user.home"), ".dta"),
    ) : this({ args -> adb.run(*args.toTypedArray()) }, adb.binaryPath, requestTimeout, dataDir)

    private val schemas = SchemaRegistry(dataDir)
    private val injectedTooling = io.yamsergey.dta.daemon.inject.InjectedTooling(dataDir)
    private val forwards = PortForwards(runAdb)

    init {
        // FR-011: forwards leaked by a previous run are cleaned at startup.
        // Never fatal — no adb/no devices just means nothing to sweep.
        if (adbPath != null) runCatching { forwards.startupSweep() }
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

    /** Keeps watched apps connected and says when that changes (feature 009, FR-017). */
    private val watcher = io.yamsergey.dta.daemon.connection.AppConnectionWatcher(
        instances = { serial -> discovery.listInstances().filter { serial == null || it.serial == serial }.map { ConnectionKey(it.serial, it.packageName) } },
        manager = connections,
    )

    /** App connection state changes on [serial] (every device when null), for as long as collected. */
    fun appEvents(serial: String?): Flow<io.yamsergey.dta.protocol.ops.AppConnectionEvent> = watcher.events(serial)

    private val foregroundDetector = ForegroundDetector(runAdb)
    private val fallback = UiAutomatorFallback(runAdb)

    /**
     * Capture the layout tree for [pkg] on [serial], applying the sidekick-vs-
     * fallback decision ladder with honest labeling (contract §4). [pkg] null
     * means "whatever is foreground" (fallback only).
     */
    suspend fun layout(
        serial: String,
        pkg: String?,
        filters: LayoutFilter? = null,
        detail: DetailLevel = DetailLevel.SEMANTIC,
        frameworkFrames: Boolean = false,
    ): LayoutCapture {
        val service = LayoutService(
            foregroundPackage = { foregroundDetector.foregroundPackage(it) },
            sidekickCapture = pkg?.let { p ->
                { params ->
                    val conn = connections.connect(ConnectionKey(serial, p))
                    val result = conn.request("layout_tree", params.toJson())
                    DtaJson.decodeFromJsonElement(LayoutCapture.serializer(), result)
                }
            },
            uiautomatorCapture = { s ->
                val screen = screenOf(s)
                fallback.capture(s, screen.widthPx, screen.heightPx)
            },
            screenOf = { screenOf(it) },
        )
        return service.capture(serial, pkg, LayoutTreeParams(filters, detail, frameworkFrames))
    }

    /** Network history of [pkg]: HTTP and WebSocket entries in one list (feature 004). */
    suspend fun network(serial: String, pkg: String, query: NetworkQuery = NetworkQuery()): NetworkPage =
        networkService(serial, pkg).list(query)

    suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean = false): NetworkEntryDetail =
        networkService(serial, pkg).entry(id, redact)

    /**
     * One body: decoded text, or bytes to a file. Protobuf bodies a rule selects (built-in
     * OTLP, user mappings) come back as JSON with [BodyResult.decoding]; other binary bodies
     * need [BodyOutput.File].
     * With [redact], bodies that cannot be redacted are withheld unless [allowUnredacted].
     */
    suspend fun networkBody(
        serial: String,
        pkg: String,
        id: String,
        part: BodyPart,
        output: BodyOutput = BodyOutput.Text,
        redact: Boolean = false,
        allowUnredacted: Boolean = false,
    ): BodyResult = networkService(serial, pkg).body(id, part, output, redact, allowUnredacted)

    suspend fun networkClear(serial: String, pkg: String): ClearResult =
        networkService(serial, pkg).clear()

    /** Live entry events; collect for as long as needed. */
    fun networkEvents(serial: String, pkg: String): Flow<NetworkEntryEvent> =
        networkService(serial, pkg).events()

    /**
     * Adds (or replaces) a protobuf schema set for [pkg]: a compiled descriptor set or
     * `.proto` sources (no protoc needed). Throws [io.yamsergey.dta.daemon.network.proto.SchemaException]
     * naming the problem. Schemas and mappings persist under the daemon's data directory.
     */
    fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput): SchemaSet = schemas.addSchema(pkg, name, input)

    fun networkSchemas(pkg: String): List<SchemaSet> = schemas.schemas(pkg)

    /** Removes a schema set; returns the mappings that used it, now broken. */
    fun networkSchemaRemove(pkg: String, name: String): List<BodyMapping> = schemas.removeSchema(pkg, name)

    /** Maps a message type to bodies: URL glob, part, and optionally method (HTTP) or direction (WebSocket). */
    fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec): BodyMapping = schemas.addMapping(pkg, mapping)

    /** User mappings (with `broken` when unusable), then the built-in OTLP rules. */
    fun networkMappings(pkg: String): List<BodyMapping> = schemas.mappings(pkg)

    fun networkMappingRemove(pkg: String, id: String): Boolean = schemas.removeMapping(pkg, id)

    /** Installs (replaces) the app's network script; it persists until [networkScriptClear]. */
    suspend fun networkScriptSet(serial: String, pkg: String, source: String): ScriptStatus =
        scriptService(serial, pkg).set(source)

    suspend fun networkScriptClear(serial: String, pkg: String): Boolean = scriptService(serial, pkg).clear()

    suspend fun networkScriptStatus(serial: String, pkg: String): ScriptStatus = scriptService(serial, pkg).status()

    suspend fun networkScriptLogs(serial: String, pkg: String, since: Long = 0): ScriptLogPage =
        scriptService(serial, pkg).logs(since)

    private fun scriptService(serial: String, pkg: String) =
        NetworkScriptService { connections.connect(ConnectionKey(serial, pkg)) }

    private fun networkService(serial: String, pkg: String) =
        NetworkService(BodyDecoding(schemas.rules(pkg))) { connections.connect(ConnectionKey(serial, pkg)) }

    private fun screenOf(serial: String): Screen {
        // wm size / density via adb, best-effort.
        val size = runAdb(listOf("-s", serial, "shell", "wm", "size")).stdout
        val (w, h) = Regex("""(\d+)x(\d+)""").find(size)?.destructured
            ?.let { (a, b) -> a.toInt() to b.toInt() } ?: (0 to 0)
        val density = runAdb(listOf("-s", serial, "shell", "wm", "density")).stdout
            .let { Regex("""(\d+)""").find(it)?.value?.toFloat()?.div(160f) ?: 1f }
        return Screen(w, h, density)
    }

    suspend fun shutdown() = connections.shutdown()

    // --- Feature 008: what the daemon process serves besides the per-app operations.

    fun adbInfo(): AdbInfo = AdbInfo(path = adbPath, available = adbPath != null, reason = adbUnavailableReason)

    /** Connected devices with their state; the model for online ones. */
    fun devices(): List<DeviceInfo> = discovery.listDevices().map { device ->
        val online = device.state == Device.State.ONLINE
        DeviceInfo(
            serial = device.serial,
            state = device.state.name.lowercase(),
            model = if (online) {
                runAdb(listOf("-s", device.serial, "shell", "getprop", "ro.product.model")).stdout.trim().ifEmpty { null }
            } else {
                null
            },
        )
    }

    /** Apps on [serial] with a running sidekick, and the foreground app. */
    fun apps(serial: String): AppList {
        val foreground = foregroundDetector.foregroundPackage(serial)
        val withDta = discovery.listInstances().filter { it.serial == serial }.map { it.packageName }.distinct()
        return AppList(serial, foreground, withDta.map { AppInfo(it, it == foreground) })
    }

    fun foregroundPackage(serial: String): String? = foregroundDetector.foregroundPackage(serial)

    /** What the app's sidekick reports about its capabilities and libraries. */
    suspend fun capabilityReport(serial: String, pkg: String): IdentityReport = injectedTooling.annotate(
        DtaJson.decodeFromJsonElement(
            IdentityReport.serializer(),
            connections.connect(ConnectionKey(serial, pkg)).request("identity"),
        ),
    )

    /** Open app connections (for `daemon.status`). */
    fun openConnections(): List<ConnectionKey> = connections.openKeys()

    companion object {
        /** A daemon whose device operations fail with [reason] (adb not found); everything else works. */
        fun withoutAdb(reason: String, dataDir: java.io.File): DtaDaemon =
            DtaDaemon({ throw AdbUnavailableException(reason) }, null, dataDir = dataDir, adbUnavailableReason = reason)
    }
}

/** adb could not be found or run; the message says where DTA looked and how to fix it. */
class AdbUnavailableException(message: String) : Exception(message)
