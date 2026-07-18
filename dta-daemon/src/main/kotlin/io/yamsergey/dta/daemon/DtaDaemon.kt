package io.yamsergey.dta.daemon

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.connection.ForwardOps
import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.SidekickConnectionManager
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
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

    suspend fun shutdown() = connections.shutdown()
}
