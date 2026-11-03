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
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The daemon is newer than this client; [message] names both versions and the fix. */
class VersionConflictException(val daemon: VersionIdentity, val client: VersionIdentity, kind: ClientKind = ClientKind.CLI) : Exception(
    if (kind == ClientKind.IDE || kind == ClientKind.MCP_HTTP) {
        "The running DTA daemon is ${daemon.display}, newer than this DTA plugin ${client.display}. " +
            "Update the DTA plugin, or stop the daemon with 'dta daemon stop' and retry to use this version."
    } else {
        "The running DTA daemon is ${daemon.display}, newer than this dta ${client.display}. " +
            "Upgrade dta, or stop the daemon with 'dta daemon stop' to use this version."
    },
)

/** The daemon did not come up; [message] points at its log. */
class DaemonStartException(message: String) : Exception(message)

/** Starts the daemon process detached; returns its pid. */
fun interface DaemonSpawner {
    fun spawn(home: File, identity: VersionIdentity, log: File): Long
}

/**
 * How to start `DaemonMain`: which Java, which classpath, which extra environment (feature 009,
 * research R4). The CLI starts it from its own process; a host whose classpath is not DTA's (the
 * IDE) passes the Java it runs on and a directory of the daemon's jars.
 */
data class DaemonLaunch(val java: File, val classpath: String, val env: Map<String, String> = emptyMap()) {
    /**
     * The same launch from a copy of its jars under [runtime], one directory per set of jars
     * (named by their names, sizes and times). A daemon loads classes lazily for as long as it
     * runs: started from a plugin's or an installation's own jars, an update of them would take
     * its classes away (NoClassDefFoundError), and a daemon that cannot even answer "replace"
     * stays behind. A development classpath (with class directories) runs where it is.
     */
    fun pinned(runtime: File): DaemonLaunch {
        val entries = classpath.split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
        if (entries.isEmpty() || entries.any { !it.isFile || !it.name.endsWith(".jar") }) return this
        if (entries.all { it.absoluteFile.startsWith(runtime.absoluteFile) }) return this
        val key = MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" }.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val target = File(runtime, key)
        val names = entries.mapIndexed { i, f -> "%03d-%s".format(i, f.name) }
        if (!File(target, COMPLETE).isFile) {
            val tmp = File(runtime, "$key.tmp-${ProcessHandle.current().pid()}-${System.nanoTime()}")
            tmp.mkdirs()
            entries.forEachIndexed { i, f -> f.copyTo(File(tmp, names[i]), overwrite = true) }
            File(tmp, COMPLETE).writeText(classpath)
            // Another starter may have made the same copy meanwhile: either is the same.
            if (!tmp.renameTo(target)) tmp.deleteRecursively()
        }
        return copy(classpath = names.joinToString(File.pathSeparator) { File(target, it).path })
    }

    companion object {
        private const val COMPLETE = ".complete"

        fun currentProcess() = DaemonLaunch(File(System.getProperty("java.home"), "bin/java"), System.getProperty("java.class.path"))

        fun fromDirectory(java: File, dir: File, env: Map<String, String> = emptyMap()): DaemonLaunch {
            val jars = dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }
            if (jars.isEmpty()) throw DaemonStartException("${dir.path} has no daemon jars; the DTA daemon cannot start from it")
            return DaemonLaunch(java, jars.joinToString(File.pathSeparator) { it.path }, env)
        }

        /**
         * Removes copies no running process uses (their path is on no command line) and older than
         * a few minutes (one being started right now is kept).
         */
        fun pruneRuntime(runtime: File, keep: Set<File>) {
            val dirs = runtime.listFiles { f -> f.isDirectory }.orEmpty().filter { it.absoluteFile !in keep.map(File::getAbsoluteFile) }
            if (dirs.isEmpty()) return
            val inUse = ProcessHandle.allProcesses().map { it.info().commandLine().orElse("") }.toList().joinToString("\n")
            val old = System.currentTimeMillis() - 10 * 60_000
            dirs.filter { it.lastModified() < old && it.absolutePath !in inUse }.forEach { it.deleteRecursively() }
        }
    }
}

/** Starts `DaemonMain` detached as [launch] describes. */
class ClasspathSpawner(private val launch: DaemonLaunch = DaemonLaunch.currentProcess()) : DaemonSpawner {
    override fun spawn(home: File, identity: VersionIdentity, log: File): Long {
        val runtime = File(home, "daemon/runtime")
        val launch = this.launch.pinned(runtime)
        runCatching { DaemonLaunch.pruneRuntime(runtime, keep = launch.classpath.split(File.pathSeparator).map { File(it).parentFile }.toSet()) }
        val process = ProcessBuilder(
            launch.java.path, "-cp", launch.classpath, MAIN,
            "--home", home.absolutePath, "--version", identity.version, "--build", identity.build,
        )
            .apply { environment().putAll(launch.env) }
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        return process.pid()
    }

    companion object {
        const val MAIN = "io.yamsergey.dta.daemon.server.DaemonMain"
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
    private val spawner: DaemonSpawner = ClasspathSpawner(),
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

    /**
     * The running daemon whatever its version, without starting, replacing or refusing —
     * for `dta daemon stop` (which must work against any version). Null when none runs.
     */
    suspend fun existing(kind: ClientKind): DaemonClient? {
        val state = readState() ?: return null
        if (!isAlive(state.pid)) return null
        val token = readToken() ?: return null
        return runCatching { DaemonClient.connect(state.port, token, kind, identity) }.getOrNull()
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
            // Alive but not answering (hung, or its jars were replaced under it): stopped, never left beside a new one.
            retire(state.pid, "did not answer")?.let { return Attempt.Stale(it) }
            return Attempt.Stale("recovered: the DTA daemon state pointed at a port that does not answer (${e.message ?: e.javaClass.simpleName})")
        }
        return when (VersionIdentity.compare(client = identity, daemon = client.daemon.identity)) {
            VersionIdentity.Comparison.SAME -> Attempt.Ready(client)
            VersionIdentity.Comparison.CLIENT_OLDER -> {
                client.close()
                throw VersionConflictException(client.daemon.identity, identity, kind)
            }
            VersionIdentity.Comparison.CLIENT_NEWER -> {
                val old = client.daemon
                runCatching { client.request(DaemonMethods.REPLACE, DaemonClient.replaceParams(identity)) }
                client.close()
                val gone = waitUntil(10.seconds) { !isAlive(old.pid) && readState()?.pid != old.pid }
                val forced = if (gone) null else retire(old.pid, "did not stop when replaced")
                Attempt.Stale(listOfNotNull("replaced the DTA daemon ${old.identity.display} with ${identity.display}", forced).joinToString("; "))
            }
        }
    }

    /**
     * Stops [pid] when it is this home's daemon (its command line names DaemonMain and this
     * home; a reused pid of another program is never touched): SIGTERM, then SIGKILL. Null when
     * it is not this home's daemon or already gone.
     */
    private suspend fun retire(pid: Long, why: String): String? {
        val process = ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive } ?: return null
        val command = process.info().commandLine().orElse("")
        if (ClasspathSpawner.MAIN !in command || home.root.absolutePath !in command) return null
        process.destroy()
        if (!waitUntil(5.seconds) { !process.isAlive }) {
            process.destroyForcibly()
            waitUntil(5.seconds) { !process.isAlive }
        }
        if (readState()?.pid == pid) stateFile.delete()
        return "the previous DTA daemon (pid $pid) $why; it was stopped"
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
                            lastLogLine().let { if (it == null) "" else ": $it" } + "; see ${logFile.path}",
                    )
                }
                val state = readState()!!
                return DaemonClient.connect(state.port, readToken() ?: throw DaemonStartException("daemon token missing; see ${logFile.path}"), kind, identity) to true
            }
        }
        }
    }

    /** The daemon's own last word, so a failed start is stated with its cause. */
    private fun lastLogLine(): String? =
        runCatching { logFile.readLines().lastOrNull { it.isNotBlank() }?.trim() }.getOrNull()

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
