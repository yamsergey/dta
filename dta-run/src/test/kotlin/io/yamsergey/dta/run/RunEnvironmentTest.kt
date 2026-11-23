package io.yamsergey.dta.run

import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.protocol.ops.ClientKind
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunEnvironmentTest {
    /**
     * MCP-STUDIO-001 (fix-s-mcp, 2026-11-23): the plugin's `app_run` opened its own DaemonLauncher with the
     * default spawner, which starts the daemon on the current process's classpath. In the IDE that is the
     * IDE's classpath (no DaemonMain), so with no daemon running yet app_run failed at resolve with
     * "ClassNotFoundException: DaemonMain". A run connects the way its host does.
     */
    @Test
    fun `a run connects through the host's own connect`() = runBlocking {
        val project = Files.createTempDirectory("run-env").toFile().also { File(it, "gradlew").writeText("#!/bin/sh\n") }
        var calls = 0
        val environment = RunEnvironment(ClientKind.MCP_HTTP, connect = { calls++; throw DaemonStartException("the host's daemon is not up") })
        val result = try { environment.session.run(RunRequest(project)) {} } finally { environment.disconnect() }
        assertEquals(1, calls, "the host's connect was used")
        assertEquals(StepName.RESOLVE, result.failure?.step)
        assertTrue("the host's daemon is not up" in result.failure!!.cause, result.failure!!.cause)
    }
}
