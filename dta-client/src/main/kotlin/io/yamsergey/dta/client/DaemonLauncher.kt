package io.yamsergey.dta.client

import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonMethods
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The daemon is newer than this client; [message] names both versions and the fix. */
class VersionConflictException(val daemon: VersionIdentity, val client: VersionIdentity) : Exception(
    "The running DTA daemon is ${daemon.display}, newer than this dta ${client.display}. " +
        "Upgrade dta, or stop the daemon with 'dta daemon stop' to use this version.",
)

/** The daemon did not come up; [message] points at its log. */
class DaemonStartException(message: String) : Exception(message)

/** Starts the daemon process detached; returns its pid. */
fun interface DaemonSpawner {
    fun spawn(home: File, identity: VersionIdentity, log: File): Long
}

/** Starts `DaemonMain` with this process's own Java and classpath (research R3). */
object ProcessSpawner : DaemonSpawner {
    const val MAIN = "io.yamsergey.dta.daemon.server.DaemonMain"

    override fun spawn(home: File, identity: VersionIdentity, log: File): Long {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), MAIN,
            "--home", home.absolutePath, "--version", identity.version, "--build", identity.build,
        )
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        return process.pid()
    }
}

/**
 * The client side of the daemon's lifecycle (feature 008, research R2): every [ensure]
 * reads the state file, connects and says hello, then reuses, replaces (client newer),
 * refuses (client older) or starts a daemon. No liveness state is kept between calls;
 * the daemon owns its own life.
 */
class DaemonLauncher(
    private val home: DtaHome = DtaHome.resolve(),
    private val version: VersionInfo = VersionInfo.current(),
    private val spawner: DaemonSpawner = ProcessSpawner,
    private val startTimeout: Duration = 20.seconds,
    private val isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) {
    /** A connected client plus what happened on the way (replaced, recovered, started). */
    class Connected(val client: DaemonClient, val statements: List<String>)

    @Serializable
    private data class State(val pid: Long, val port: Int, val version: String, val build: String)

    private val dir = home.daemonDir
    private val stateFile = File(dir, "daemon.json")
    private val json = Json { ignoreUnknownKeys = true }

    val identity = VersionIdentity(version.version, version.build, PROTOCOL_VERSION)
    val logFile = File(dir, "daemon.log")

    suspend fun ensure(kind: ClientKind): Connected {
        val statements = mutableListOf<String>()
        repeat(3) {
            when (val attempt = attach(kind)) {
                is Attempt.Ready -> return Connected(attempt.client, statements)
                is Attempt.Stale -> attempt.statement?.let(statements::add)
                Attempt.None -> Unit
            }
            start(kind)?.let { (client, spawned) ->
                return Connected(client, if (spawned) statements + "started the DTA daemon ${identity.display}" else statements)
            }
        }
        throw DaemonStartException("Could not reach a DTA daemon after three attempts; see ${logFile.path}")
    }

    private sealed interface Attempt {
        data class Ready(val client: DaemonClient) : Attempt
        data class Stale(val statement: String?) : Attempt
        data object None : Attempt
    }

    /** Connects to the daemon the state file names, handling version differences. */
    private suspend fun attach(kind: ClientKind): Attempt {
        val state = readState() ?: return Attempt.None
        if (!isAlive(state.pid)) {
            stateFile.delete()
            return Attempt.Stale("recovered: the previous DTA daemon (pid ${state.pid}) was no longer running")
        }
        val token = readToken() ?: return Attempt.Stale("recovered: the DTA daemon token was missing")
        val client = try {
            DaemonClient.connect(state.port, token, kind, identity)
        } catch (e: Exception) {
            return Attempt.Stale("recovered: the DTA daemon state pointed at a port that does not answer (${e.message ?: e.javaClass.simpleName})")
        }
        return when (VersionIdentity.compare(client = identity, daemon = client.daemon.identity)) {
            VersionIdentity.Comparison.SAME -> Attempt.Ready(client)
            VersionIdentity.Comparison.CLIENT_OLDER -> {
                client.close()
                throw VersionConflictException(client.daemon.identity, identity)
            }
            VersionIdentity.Comparison.CLIENT_NEWER -> {
                val old = client.daemon
                runCatching { client.request(DaemonMethods.REPLACE, DaemonClient.replaceParams(identity)) }
                client.close()
                waitUntil(10.seconds) { !isAlive(old.pid) && readState()?.pid != old.pid }
                Attempt.Stale("replaced the DTA daemon ${old.identity.display} with ${identity.display}")
            }
        }
    }

    /** Starts a daemon under the start lock (one starter at a time); `false` when another starter won meanwhile. */
    private suspend fun start(kind: ClientKind): Pair<DaemonClient, Boolean>? {
        dir.mkdirs()
        // The file lock serializes processes; the mutex serializes threads of this process
        // (a JVM refuses overlapping locks on one file).
        val inProcess = startLocks.computeIfAbsent(dir.canonicalPath) { Mutex() }
        inProcess.withLock {
        RandomAccessFile(File(dir, "start.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                // Another client may have started one while we waited for the lock.
                attachQuietly(kind)?.let { return it to false }
                rotateLog()
                val pid = spawner.spawn(home.root, identity, logFile)
                val up = waitUntil(startTimeout) { readState()?.pid == pid || !isAlive(pid) }
                if (!up || readState()?.pid != pid) {
                    throw DaemonStartException(
                        "The DTA daemon did not start" + (if (!isAlive(pid)) " (it exited)" else " within $startTimeout") +
                            "; see ${logFile.path}",
                    )
                }
                val state = readState()!!
                return DaemonClient.connect(state.port, readToken() ?: throw DaemonStartException("daemon token missing; see ${logFile.path}"), kind, identity) to true
            }
        }
        }
    }

    private suspend fun attachQuietly(kind: ClientKind): DaemonClient? {
        val state = readState() ?: return null
        if (!isAlive(state.pid)) return null
        val token = readToken() ?: return null
        val client = runCatching { DaemonClient.connect(state.port, token, kind, identity) }.getOrNull() ?: return null
        if (VersionIdentity.compare(identity, client.daemon.identity) == VersionIdentity.Comparison.SAME) return client
        client.close()
        return null
    }

    private companion object {
        val startLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    }

    private fun readState(): State? = runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrNull()

    private fun readToken(): String? = runCatching { File(dir, "token").readText().trim() }.getOrNull()

    /** daemon.log → .1 → .2 → .3 at 10 MB. */
    private fun rotateLog() {
        if (!logFile.exists() || logFile.length() < 10L * 1024 * 1024) return
        for (i in 2 downTo 1) File(dir, "daemon.log.$i").takeIf { it.exists() }?.renameTo(File(dir, "daemon.log.${i + 1}"))
        logFile.renameTo(File(dir, "daemon.log.1"))
    }

    private suspend fun waitUntil(timeout: Duration, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            delay(50)
        }
        return check()
    }
}
