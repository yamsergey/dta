package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.ClasspathSpawner
import io.yamsergey.dta.client.DaemonLaunch
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.DtaHome
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real daemon processes (DaemonMain from this test classpath), one isolated home per test. */
class DaemonLauncherTest {
    private val home = DtaHome(Files.createTempDirectory("dta-launch").toFile())
    private val state = StateFile(home.daemonDir)
    private fun launcher(build: String = "2026-10-28T10:00:00Z") = DaemonLauncher(home, VersionInfo("0.1.0", build, "test"))

    @AfterTest
    fun stopAll() = runBlocking {
        runCatching { launcher("9999").ensure(ClientKind.CLI).client.call("daemon.stop") }
        Unit
    }

    private fun kill9(pid: Long) {
        ProcessBuilder("kill", "-9", pid.toString()).start().waitFor()
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(20)
    }

    @Test
    fun `starts once, then reuses`() = runBlocking<Unit> {
        val first = launcher().ensure(ClientKind.CLI)
        assertTrue(first.statements.single().startsWith("started the DTA daemon"), "${first.statements}")
        val pid = first.client.daemon.pid
        first.client.close()
        val second = launcher().ensure(ClientKind.MCP)
        assertEquals(pid, second.client.daemon.pid)
        assertEquals(emptyList(), second.statements)
        second.client.close()
    }

    @Test
    fun `a killed daemon is recovered and the recovery stated`() = runBlocking<Unit> {
        val first = launcher().ensure(ClientKind.CLI)
        val pid = first.client.daemon.pid
        kill9(pid)
        val next = launcher().ensure(ClientKind.CLI)
        assertNotEquals(pid, next.client.daemon.pid)
        assertTrue(next.statements.any { "no longer running" in it || "does not answer" in it }, "${next.statements}")
        next.client.close()
    }

    @Test
    fun `a foreign listener on the recorded port is never used`() = runBlocking<Unit> {
        val foreign = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { runCatching { while (true) foreign.accept().use { it.getOutputStream().write("HTTP/1.1 404 Nope\r\n\r\n".toByteArray()) } } }
        state.write(DaemonState(ProcessHandle.current().pid(), foreign.localPort, "0.1.0", "2026-10-28T10:00:00Z", 1, 0))
        File(home.daemonDir, "token").writeText("x")
        val connected = launcher().ensure(ClientKind.CLI)
        assertTrue(connected.statements.any { "does not answer" in it }, "${connected.statements}")
        assertNotEquals(foreign.localPort, state.read()!!.port)
        connected.client.close()
        foreign.close()
    }

    @Test
    fun `a newer client replaces the daemon, an older one refuses`() = runBlocking<Unit> {
        val old = launcher("2026-10-28T10:00:00Z").ensure(ClientKind.CLI)
        val oldPid = old.client.daemon.pid
        old.client.close()

        val newer = launcher("2026-10-29T10:00:00Z").ensure(ClientKind.CLI)
        assertNotEquals(oldPid, newer.client.daemon.pid)
        assertTrue(newer.statements.any { it.startsWith("replaced the DTA daemon 0.1.0 (build 2026-10-28T10:00:00Z)") }, "${newer.statements}")
        newer.client.close()

        val refused = assertFailsWith<VersionConflictException> { launcher("2026-10-28T10:00:00Z").ensure(ClientKind.CLI) }
        assertTrue("dta daemon stop" in refused.message!!)
        assertEquals(newer.client.daemon.pid, state.read()!!.pid, "the refusing client leaves the newer daemon alone")
    }

    @Test
    fun `ten clients at once start exactly one daemon`() = runBlocking<Unit> {
        val connected = (1..10).map { async(Dispatchers.IO) { launcher().ensure(ClientKind.CLI) } }.awaitAll()
        assertEquals(1, connected.map { it.client.daemon.pid }.toSet().size)
        assertEquals(1, connected.count { c -> c.statements.any { it.startsWith("started") } })
        connected.forEach { it.client.close() }
    }

    // --- No daemon left behind (a replaced or unresponsive one is retired; one that lost its home leaves)

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun waitGone(pid: Long, ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) { if (!alive(pid)) return true; Thread.sleep(50) }
        return !alive(pid)
    }

    @Test
    fun `an unresponsive daemon is retired, never left running beside the new one`() = runBlocking<Unit> {
        val first = launcher().ensure(ClientKind.CLI)
        val pid = first.client.daemon.pid
        first.client.close()
        ProcessBuilder("kill", "-STOP", pid.toString()).start().waitFor()
        try {
            val next = launcher().ensure(ClientKind.CLI)
            assertNotEquals(pid, next.client.daemon.pid)
            assertTrue(waitGone(pid, 10_000), "the unresponsive daemon $pid is still running")
            assertTrue(next.statements.any { "pid $pid" in it && "did not answer" in it && "stopped" in it }, "${next.statements}")
            next.client.close()
        } finally {
            ProcessBuilder("kill", "-9", pid.toString()).start().waitFor()
        }
    }

    @Test
    fun `a process that is not this home's daemon is never stopped`() = runBlocking<Unit> {
        // The state file names a live process that is not a DTA daemon (a reused pid): it is left alone.
        val other = ProcessBuilder("sleep", "60").start()
        try {
            state.write(DaemonState(other.pid(), 1, "0.1.0", "2026-10-28T10:00:00Z", 1, 0))
            File(home.daemonDir, "token").writeText("x")
            val connected = launcher().ensure(ClientKind.CLI)
            assertTrue(other.isAlive, "a process that is not a DTA daemon was stopped")
            connected.client.close()
        } finally {
            other.destroyForcibly()
        }
    }

    @Test
    fun `a daemon whose home another daemon took over leaves by itself`() = runBlocking<Unit> {
        val first = launcher().ensure(ClientKind.CLI)
        val pid = first.client.daemon.pid
        first.client.close()
        // As when a client started a second daemon over it: the state file names someone else.
        val other = ProcessBuilder("sleep", "60").start()
        try {
            state.write(DaemonState(other.pid(), 1, "0.1.0", "2026-10-28T10:00:00Z", 1, 0))
            assertTrue(waitGone(pid, 15_000), "daemon $pid kept running after losing its home")
            assertTrue(File(home.daemonDir, "daemon.log").readText().contains("another DTA daemon (pid ${other.pid()}) serves"), "log says why")
        } finally {
            other.destroyForcibly()
            state.stateFile.delete()
        }
    }

    @Test
    fun `a launch from a directory runs from a copy, so replacing the jars never breaks a running daemon`() {
        val lib = Files.createTempDirectory("dta-daemon-lib").toFile()
        File(lib, "a.jar").writeText("one")
        File(lib, "b.jar").writeText("two")
        val runtime = File(home.daemonDir, "runtime")
        val first = DaemonLaunch.fromDirectory(File("/jbr/bin/java"), lib).pinned(runtime)
        val entries = first.classpath.split(File.pathSeparator).map(::File)
        assertTrue(entries.all { it.path.startsWith(runtime.path) && it.isFile }, first.classpath)
        assertEquals(listOf("one", "two"), entries.map { it.readText() })
        // The same jars: the same copy. Replaced jars (a plugin update): a new copy; the old one stays for whoever runs from it.
        assertEquals(first.classpath, DaemonLaunch.fromDirectory(File("/jbr/bin/java"), lib).pinned(runtime).classpath)
        File(lib, "b.jar").writeText("two, updated")
        val second = DaemonLaunch.fromDirectory(File("/jbr/bin/java"), lib).pinned(runtime)
        assertNotEquals(first.classpath, second.classpath)
        assertEquals("two", entries[1].readText())
    }

    // --- Starting from any host (feature 009, research R4)

    @Test
    fun `the current process launch is this JVM's java and classpath`() {
        val launch = DaemonLaunch.currentProcess()
        assertEquals(File(System.getProperty("java.home"), "bin/java"), launch.java)
        assertEquals(System.getProperty("java.class.path"), launch.classpath)
        assertEquals(emptyMap(), launch.env)
    }

    @Test
    fun `a launch from a directory puts every jar in it on the classpath, in a stable order`() {
        val dir = Files.createTempDirectory("dta-daemon-lib").toFile()
        listOf("b.jar", "a.jar", "notes.txt").forEach { File(dir, it).writeText("") }
        val launch = DaemonLaunch.fromDirectory(File("/jbr/bin/java"), dir, mapOf("DTA_ADB" to "/sdk/adb"))
        assertEquals(listOf(File(dir, "a.jar").path, File(dir, "b.jar").path).joinToString(File.pathSeparator), launch.classpath)
        assertEquals(mapOf("DTA_ADB" to "/sdk/adb"), launch.env)
    }

    @Test
    fun `a launch from a directory without jars says so instead of starting a daemon that cannot load`() {
        val dir = Files.createTempDirectory("dta-daemon-empty").toFile()
        val e = assertFailsWith<DaemonStartException> { DaemonLaunch.fromDirectory(File("/jbr/bin/java"), File(dir, "daemon")) }
        assertTrue("${File(dir, "daemon").path}" in e.message!! && "no daemon jars" in e.message!!, e.message)
    }

    @Test
    fun `a daemon that exits at start is stated with the last line of its log`() = runBlocking<Unit> {
        val java = File(System.getProperty("java.home"), "bin/java")
        val launch = DaemonLaunch(java, Files.createTempDirectory("dta-no-classes").toString())
        val e = assertFailsWith<DaemonStartException> {
            DaemonLauncher(home, VersionInfo("0.1.0", "2026-10-28T10:00:00Z", "test"), ClasspathSpawner(launch)).ensure(ClientKind.CLI)
        }
        assertTrue("(it exited)" in e.message!! && "ClassNotFoundException" in e.message!!, e.message)
    }

    @Test
    fun `an explicit launch starts a real daemon and its environment reaches it`() = runBlocking<Unit> {
        // A path to adb no other lookup would find: the daemon reports it only if DTA_ADB arrived.
        val adb = File(Files.createTempDirectory("dta-adb").toFile(), "adb")
        Files.createSymbolicLink(adb.toPath(), File(AdbExec.resolveAdb()).toPath())
        val java = File(System.getProperty("java.home"), "bin/java")
        val launch = DaemonLaunch(java, System.getProperty("java.class.path"), mapOf("DTA_ADB" to adb.path))
        val connected = DaemonLauncher(home, VersionInfo("0.1.0", "2026-10-28T10:00:00Z", "test"), ClasspathSpawner(launch)).ensure(ClientKind.CLI)
        val status = DtaJson.decodeFromJsonElement(DaemonStatus.serializer(), connected.client.call("daemon.status").result)
        assertEquals(adb.path, status.adb.path)
        connected.client.close()
    }
}
