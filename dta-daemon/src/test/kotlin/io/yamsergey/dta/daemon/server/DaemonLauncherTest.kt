package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DtaHome
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.protocol.ops.ClientKind
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
        val foreign = ServerSocket(0)
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
}
