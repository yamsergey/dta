package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.DtaDaemon
import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.protocol.ops.ConnectionInfo
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.runBlocking
import sun.misc.Signal
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The daemon process (feature 008, research R2/R3). Started by the launcher from the same
 * distribution: `DaemonMain --home <dir> --version <v> --build <b>`. Ignores SIGINT and
 * SIGHUP (a Ctrl-C on a client or a closed terminal must not kill it); stops on SIGTERM,
 * `daemon.stop`, or replacement by a newer version.
 */
object DaemonMain {
    private val stopped = CountDownLatch(1)
    private val stopping = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
        val home = File(opts["home"] ?: "${System.getProperty("user.home")}/.dta")
        val identity = VersionIdentity(opts["version"] ?: "dev", opts["build"] ?: "unknown", PROTOCOL_VERSION)
        val started = System.currentTimeMillis()
        log("starting ${identity.display}, home $home, pid ${ProcessHandle.current().pid()}")

        val daemon = try {
            DtaDaemon(AdbExec(), dataDir = home)
        } catch (e: IllegalStateException) {
            log("adb unavailable: ${e.message}")
            DtaDaemon.withoutAdb(e.message ?: "adb not found", home)
        }
        val ops = LibraryOperations(daemon)
        val selector = Selector(ops::devices, ops::apps)
        lateinit var server: DaemonServer

        fun shutdown(reason: String) {
            if (!stopping.compareAndSet(false, true)) return
            log("stopping: $reason")
            server.stop(reason)
            runCatching { runBlocking { daemon.shutdown() } }
            stopped.countDown()
        }

        server = DaemonServer(
            identity = identity,
            stateFile = StateFile(File(home, "daemon")),
            dispatcher = OperationDispatcher(ops, selector, identity.version),
            ops = ops,
            selector = selector,
            status = { clients ->
                DaemonStatus(
                    identity = identity,
                    pid = ProcessHandle.current().pid(),
                    uptimeMs = System.currentTimeMillis() - started,
                    home = home.absolutePath,
                    adb = daemon.adbInfo(),
                    clients = clients,
                    devices = runCatching { daemon.devices() }.getOrDefault(emptyList()),
                    connections = daemon.openConnections().map { ConnectionInfo(it.serial, it.packageName, "connected") },
                )
            },
            onStop = ::shutdown,
        )
        server.start()
        log("listening on 127.0.0.1:${server.port}")

        // One daemon per home: when daemon.json names another live daemon (a client started one
        // over this), this one leaves instead of running on unseen.
        val me = ProcessHandle.current().pid()
        val state = StateFile(File(home, "daemon"))
        thread(name = "dta-daemon-owner", isDaemon = true) {
            while (!stopping.get()) {
                Thread.sleep(OWNER_CHECK_MS)
                val owner = state.read()?.pid ?: continue
                if (owner != me && ProcessHandle.of(owner).map { it.isAlive }.orElse(false)) shutdown("another DTA daemon (pid $owner) serves $home")
            }
        }

        runCatching { Signal.handle(Signal("INT")) { log("ignoring SIGINT") } }
        runCatching { Signal.handle(Signal("HUP")) { log("ignoring SIGHUP") } }
        runCatching { Signal.handle(Signal("TERM")) { Thread { shutdown("terminated (SIGTERM)") }.start() } }

        stopped.await()
        log("stopped")
        exitProcess(0)
    }

    private const val OWNER_CHECK_MS = 3_000L

    private fun log(message: String) = println("${Instant.now()} $message")
}
