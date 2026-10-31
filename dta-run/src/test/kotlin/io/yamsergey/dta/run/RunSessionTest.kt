package io.yamsergey.dta.run

import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.InjectionReport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T015 (US1): the run's success path against fakes. */
class RunSessionTest {

    @Test
    fun `a run resolves, builds, launches and connects, and reports each step`() = runBlocking<Unit> {
        val h = Harness()
        val result = h.run()
        assertEquals(RunOutcome.CONNECTED, result.outcome, "$result")
        assertEquals(0, result.exitCode)
        assertEquals(listOf(StepName.RESOLVE, StepName.BUILD, StepName.LAUNCH, StepName.CONNECT), result.steps.map { it.step })
        assertTrue(result.steps.all { it.outcome == StepOutcome.OK })
        assertEquals(DeviceSummary(SERIAL, "Pixel 8"), result.device)
        assertEquals(":app", result.module)
        assertEquals("debug", result.variant)
        assertEquals(APP_ID, result.applicationId)
        assertEquals("$APP_ID/.MainActivity", result.activity)
        assertEquals(InjectionOutcome.INJECTED, result.injection!!.outcome)
        assertNull(result.failure)
        assertTrue(File(result.log!!).isFile, "the build log is kept")
        assertNotNull(result.steps.first { it.step == StepName.BUILD }.phases)
    }

    @Test
    fun `Gradle gets the init script, the report and request paths, the device and dtaRun`() = runBlocking<Unit> {
        val h = Harness()
        h.run(RunRequest(fakeProject(), gradleArgs = listOf("--offline")))
        val args = h.gradle.args
        assertTrue("--console=plain" in args)
        val script = File(args[args.indexOf("--init-script") + 1])
        assertTrue(script.readText().contains("io.github.yamsergey:dta-sidekick:0.11.0"), "the script injects this release")
        assertNotNull(h.gradle.property(args, "dta.inject.report"))
        assertEquals(listOf("dtaRun", "--offline"), args.takeLast(2), "dtaRun, then the user's Gradle arguments")
        assertEquals(SERIAL, h.gradle.env["ANDROID_SERIAL"], "AGP would otherwise install on every device")
        assertEquals(JsonPrimitive(false), h.gradle.request!!["buildOnly"])
        assertNull(h.gradle.property(args, "dta.inject.tooling"), "tooling stays on by default")
    }

    @Test
    fun `the connection is watched before the launch, so no event is missed`() = runBlocking<Unit> {
        val h = Harness()
        h.run()
        assertEquals(listOf("choose:null", "watch"), h.daemon.calls)
        assertEquals(1, h.device.calls.takeWhile { it != "start" }.count { it == "pidof" }, "the old pid is read just before the launch")
        assertTrue(h.daemon.closed)
    }

    @Test
    fun `a connected event from the old process does not count`() = runBlocking<Unit> {
        // Still running before the launch (pid 100); the first "connected" still has pid 100, the next one is the new process.
        val h = Harness(device = FakeDevice(pids = mutableListOf(100, 100, 200)), daemon = FakeDaemon(outcomes = mutableListOf(ConnectionOutcome.Connected, ConnectionOutcome.Connected)))
        val result = h.run()
        assertEquals(RunOutcome.CONNECTED, result.outcome)
        assertEquals(true, result.steps.first { it.step == StepName.LAUNCH }.restarted, "the app was running: it was restarted")
    }

    @Test
    fun `an app the install stopped still counts as restarted`() = runBlocking<Unit> {
        // Running before the build (the last run names the app); installing the update ended it before the launch.
        val h = Harness(device = FakeDevice(pids = mutableListOf(100, null, 300)))
        val result = h.run(RunRequest(h.ranBefore()))
        assertEquals(true, result.steps.first { it.step == StepName.LAUNCH }.restarted)
        assertEquals("pidof", h.device.calls.first(), "read before the build")
    }

    @Test
    fun `progress names each step and phase`() = runBlocking<Unit> {
        val h = Harness()
        h.run()
        val started = h.progress.filterIsInstance<RunProgress.StepStarted>().map { it.step }
        assertEquals(listOf(StepName.RESOLVE, StepName.BUILD, StepName.LAUNCH, StepName.CONNECT), started)
        assertEquals(listOf(Phase.COMPILE, Phase.PACKAGE, Phase.INSTALL), h.progress.filterIsInstance<RunProgress.PhaseStarted>().map { it.phase })
        assertEquals(4, h.progress.filterIsInstance<RunProgress.StepDone>().size)
    }

    @Test
    fun `the report is kept per application id for the daemon's capability`() = runBlocking<Unit> {
        val h = Harness()
        h.run()
        val kept = File(h.home, "injection/$APP_ID.json")
        assertTrue(kept.isFile)
        assertEquals(InjectionOutcome.INJECTED, InjectionReport.parse(kept.readText()).projects.single().outcome)
    }

    @Test
    fun `a project-provided sidekick counts as DTA`() = runBlocking<Unit> {
        val h = Harness(gradle = FakeGradle(report = selectedReport(InjectionOutcome.PROJECT_PROVIDED)))
        assertEquals(RunOutcome.CONNECTED, h.run().outcome)
    }

    @Test
    fun `no-launch stops after the install, build-only needs no device`() = runBlocking<Unit> {
        val installed = Harness().run(RunRequest(fakeProject(), mode = RunMode.NO_LAUNCH))
        assertEquals(RunOutcome.INSTALLED, installed.outcome)
        assertEquals(0, installed.exitCode)

        val h = Harness()
        val built = h.run(RunRequest(fakeProject(), mode = RunMode.BUILD_ONLY))
        assertEquals(RunOutcome.BUILT, built.outcome)
        assertEquals(0, built.exitCode)
        assertTrue(h.daemon.calls.isEmpty(), "build-only never asks for a device")
        assertEquals(JsonPrimitive(true), h.gradle.request!!["buildOnly"])
        assertFalse("ANDROID_SERIAL" in h.gradle.env)
    }
}
