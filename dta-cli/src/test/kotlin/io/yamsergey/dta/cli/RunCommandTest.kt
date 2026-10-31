package io.yamsergey.dta.cli

import io.yamsergey.dta.cli.format.RunFormat
import io.yamsergey.dta.cli.format.Style
import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.ProjectInjection
import io.yamsergey.dta.inject.ToolingInjection
import io.yamsergey.dta.inject.ToolingOutcome
import io.yamsergey.dta.run.DeviceSummary
import io.yamsergey.dta.run.Failure
import io.yamsergey.dta.run.Phase
import io.yamsergey.dta.run.RunMode
import io.yamsergey.dta.run.RunOutcome
import io.yamsergey.dta.run.RunResult
import io.yamsergey.dta.run.Step
import io.yamsergey.dta.run.StepName
import io.yamsergey.dta.run.StepOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** T021 (US1/US3): `dta run` options become a RunRequest; the result prints per contracts/run.md. */
class RunCommandTest {
    private val cwd: File = Files.createTempDirectory("dta-cli-run").toFile()
    private var invocation: RunInvocation? = null

    private fun cli(vararg args: String): Int {
        val out = ByteArrayOutputStream()
        return Cli(runner = { _, _, _ -> 0 }, mcp = { 0 }, run = { invocation = it; 15 }, out = PrintStream(out), err = PrintStream(ByteArrayOutputStream()), cwd = cwd)
            .run(arrayOf(*args))
    }

    @Test
    fun `defaults run the working folder's app on the chosen device`() {
        assertEquals(15, cli("run"), "the run's exit code is the command's")
        val inv = invocation!!
        assertEquals(cwd, inv.request.project)
        assertEquals(RunMode.RUN, inv.request.mode)
        assertTrue(inv.request.inject && inv.request.tooling && !inv.request.reinstall)
        assertEquals(30.seconds, inv.request.timeout)
        assertNull(inv.request.device)
        assertFalse(inv.watch || inv.verbose || inv.globals.json)
    }

    @Test
    fun `every option reaches the request`() {
        cli(
            "-d", "emulator-5554", "--json", "run", "--project", "shop", "--module", ":app", "--variant", "freeDebug", "--activity", ".Deep",
            "--no-launch", "--no-tooling", "--reinstall", "--timeout", "90", "--verbose", "--", "--offline", "-Pfoo=bar",
        )
        val r = invocation!!.request
        assertEquals(File(cwd, "shop").canonicalFile, r.project.canonicalFile)
        assertEquals(":app", r.module)
        assertEquals("freeDebug", r.variant)
        assertEquals(".Deep", r.activity)
        assertEquals("emulator-5554", r.device)
        assertEquals(RunMode.NO_LAUNCH, r.mode)
        assertFalse(r.tooling)
        assertTrue(r.reinstall)
        assertEquals(90.seconds, r.timeout)
        assertEquals(listOf("--offline", "-Pfoo=bar"), r.gradleArgs)
        assertTrue(invocation!!.verbose && invocation!!.globals.json)
    }

    @Test
    fun `build-only, no-inject and watch`() {
        cli("run", "--build-only", "--no-inject")
        assertEquals(RunMode.BUILD_ONLY, invocation!!.request.mode)
        assertFalse(invocation!!.request.inject)
        cli("run", "--watch")
        assertTrue(invocation!!.watch)
    }

    @Test
    fun `options that contradict each other are usage errors`() {
        assertEquals(ExitCodes.USAGE, cli("run", "--build-only", "--reinstall"))
        assertEquals(ExitCodes.USAGE, cli("run", "--build-only", "--no-launch"))
        assertEquals(ExitCodes.USAGE, cli("run", "--build-only", "--watch"))
        assertEquals(ExitCodes.USAGE, cli("run", "--timeout", "0"))
    }

    @Test
    fun `run is listed in the help`() {
        val out = ByteArrayOutputStream()
        Cli(runner = { _, _, _ -> 0 }, mcp = { 0 }, out = PrintStream(out)).run(arrayOf("--help"))
        assertTrue(Regex("""\brun\b.*Build""").containsMatchIn(out.toString()), out.toString())
    }

    private val connected = RunResult(
        RunOutcome.CONNECTED, 0, DeviceSummary("emulator-5554", "Pixel 8"), "/work/shop", ":app", "debug", "io.example.shop", "io.example.shop/.MainActivity",
        ProjectInjection(":app", InjectionOutcome.INJECTED, tooling = ToolingInjection(ToolingOutcome.INJECTED, "1.11.4")),
        listOf(
            Step(StepName.RESOLVE, StepOutcome.OK, 120),
            Step(StepName.BUILD, StepOutcome.OK, 41_200, mapOf(Phase.CONFIGURE to 3_100, Phase.COMPILE to 31_000, Phase.PACKAGE to 4_400, Phase.INSTALL to 2_700)),
            Step(StepName.LAUNCH, StepOutcome.OK, 900, restarted = true),
            Step(StepName.CONNECT, StepOutcome.OK, 1_300),
        ),
        "/home/.dta/logs/run-1.log",
    )

    @Test
    fun `a connected run prints the contract's summary`() {
        val text = RunFormat(Style.PLAIN, sidekickVersion = "0.11.0").summary(connected)
        val lines = text.lines()
        assertEquals("Device   emulator-5554 (Pixel 8)", lines[0])
        assertEquals("Project  /work/shop  module :app  variant debug", lines[1])
        assertEquals("Build    ok 41.2 s  (configure 3.1 s · compile 31.0 s · package 4.4 s · install 2.7 s)", lines[2])
        assertEquals("DTA      ok injected dta-sidekick 0.11.0 · Compose tooling data 1.11.4 added", lines[3])
        assertEquals("Launch   ok io.example.shop/.MainActivity (restarted)", lines[4])
        assertEquals("DTA      ok connected in 1.3 s", lines[5])
        assertEquals("Log      /home/.dta/logs/run-1.log", lines[6])
    }

    @Test
    fun `a failure prints the step, cause, next step and log`() {
        val failed = connected.copy(
            outcome = RunOutcome.FAILED, exitCode = 11,
            steps = connected.steps.take(1) + Step(StepName.BUILD, StepOutcome.FAILED, 9_000),
            failure = Failure(StepName.BUILD, "The build failed: Unresolved reference 'pay'. (/work/Main.kt:12:5)", "Fix the error and run again; the full output is in /home/.dta/logs/run-1.log."),
        )
        val text = RunFormat(Style.PLAIN, "0.11.0").summary(failed)
        assertTrue("Build    x failed 9.0 s" in text, text)
        assertTrue("The build failed: Unresolved reference 'pay'." in text, text)
        assertTrue("Next: Fix the error" in text, text)
    }

    @Test
    fun `json is the result document`() {
        val doc = Json.parseToJsonElement(RunFormat.json(connected)).jsonObject
        assertEquals("connected", doc["outcome"]!!.jsonPrimitive.content)
        assertEquals("injected", doc["injection"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        assertTrue(doc.containsKey("failure"), "null fields are present, as in the contract")
    }
}
