package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.VersionIdentity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonMainTest {

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `runs as a child process, survives SIGINT, stops on request`() {
        val home = Files.createTempDirectory("dta-main").toFile()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), DaemonMain::class.java.name,
            "--home", home.path, "--version", "0.1.0", "--build", "2026-10-28T10:00:00Z",
        ).redirectErrorStream(true).redirectOutput(File(home, "out.log")).start()
        try {
            val state = StateFile(File(home, "daemon"))
            waitUntil("daemon.json") { state.read() != null }
            assertEquals(process.pid(), state.read()!!.pid)

            ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor()
            Thread.sleep(500)
            assertTrue(process.isAlive, "SIGINT must not stop the daemon")

            runBlocking {
                val client = DaemonClient.connect(state.read()!!.port, state.token()!!, ClientKind.CLI, VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1))
                client.call("daemon.stop")
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "daemon exits after daemon.stop")
            assertFalse(state.stateFile.exists())
            assertTrue("ignoring SIGINT" in File(home, "out.log").readText())
        } finally {
            process.destroyForcibly()
        }
    }
}
