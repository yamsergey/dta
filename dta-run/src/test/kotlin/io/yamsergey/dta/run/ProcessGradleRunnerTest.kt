package io.yamsergey.dta.run

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T018: the wrapper as a process, with a fake gradlew. */
class ProcessGradleRunnerTest {
    private fun project(script: String): File = Files.createTempDirectory("dta-gradlew").toFile().apply {
        File(this, "gradlew").apply { writeText("#!/bin/sh\n$script\n"); setExecutable(true) }
    }

    @Test
    fun `lines, the log, the arguments, the environment and the exit code`() = runBlocking<Unit> {
        val dir = project("""echo "args: $*"; echo "serial: ${'$'}ANDROID_SERIAL"; echo "> Task :app:installDebug" 1>&2; exit 3""")
        val log = File(dir, "logs/run.log")
        val lines = mutableListOf<String>()
        val code = ProcessGradleRunner().run(dir, listOf("--console=plain", "dtaRun"), mapOf("ANDROID_SERIAL" to "emulator-5554"), log) { lines += it }
        assertEquals(3, code)
        assertEquals(listOf("args: --console=plain dtaRun", "serial: emulator-5554", "> Task :app:installDebug"), lines)
        assertEquals(lines, log.readLines(), "stdout and stderr both go to the log")
    }

    @Test
    fun `cancelling the run stops Gradle`() = runBlocking<Unit> {
        val dir = project("echo started; sleep 30; echo never")
        val lines = mutableListOf<String>()
        val job = launch { ProcessGradleRunner().run(dir, emptyList(), emptyMap(), File(dir, "log")) { lines += it } }
        while (lines.isEmpty()) delay(20)
        val start = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "cancelled promptly")
        delay(200)
        assertEquals(listOf("started"), lines)
    }
}
