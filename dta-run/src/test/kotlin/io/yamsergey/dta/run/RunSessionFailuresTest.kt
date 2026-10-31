package io.yamsergey.dta.run

import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.RunSelection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.yamsergey.dta.inject.RunOutcome as Selection

/** T016 (US2): every failure names the step, the cause and the next step, with its own exit code. */
class RunSessionFailuresTest {

    private fun RunResult.failed(step: StepName, code: ExitCode): Failure {
        assertEquals(RunOutcome.FAILED, outcome, "$this")
        assertEquals(code.code, exitCode, "$this")
        val f = assertNotNull(failure)
        assertEquals(step, f.step)
        assertTrue(f.cause.isNotBlank() && f.next.isNotBlank(), "$f")
        assertEquals(StepOutcome.FAILED, steps.last().outcome, "the failing step is the last one reported")
        return f
    }

    @Test
    fun `a folder without a Gradle wrapper is not an Android project`() = runBlocking<Unit> {
        val h = Harness()
        val f = h.run(RunRequest(Files.createTempDirectory("empty").toFile())).failed(StepName.RESOLVE, ExitCode.PROJECT)
        assertTrue("gradlew" in f.cause, f.cause)
        assertTrue("--project" in f.next, f.next)
        assertEquals(0, h.gradle.runs)
    }

    @Test
    fun `no app module, several modules or variants, and release each stop before building`() = runBlocking<Unit> {
        listOf(
            RunSelection(Selection.NO_APP_MODULE, reason = "no project applies com.android.application") to "no Android app module",
            RunSelection(Selection.MODULE_REQUIRED, choices = listOf(":app", ":wear")) to "--module",
            RunSelection(Selection.MODULE_NOT_FOUND, choices = listOf(":app")) to "--module",
            RunSelection(Selection.VARIANT_REQUIRED, module = ":app", choices = listOf("freeDebug", "paidDebug")) to "--variant",
            RunSelection(Selection.VARIANT_NOT_FOUND, module = ":app", choices = listOf("debug", "release")) to "--variant",
            RunSelection(Selection.NOT_DEBUGGABLE, module = ":app", variant = "release", reason = "variant release uses build type release, which is not debuggable") to "debuggable",
        ).forEach { (selection, expected) ->
            val h = Harness(gradle = FakeGradle(report = selectedReport(selection = selection), lines = listOf("> Task :dtaRun", "BUILD SUCCESSFUL")))
            val result = h.run()
            val f = result.failed(StepName.RESOLVE, ExitCode.PROJECT)
            assertTrue(expected in f.cause + " " + f.next, "${selection.outcome}: $f")
            selection.choices?.let { choices ->
                assertEquals(JsonArray(choices.map(::JsonPrimitive)), f.details!!["choices"], "the choices are listed")
                choices.forEach { assertTrue(it in f.cause + f.next, "$it is named: $f") }
            }
            assertTrue(h.device.started.isEmpty())
        }
    }

    @Test
    fun `a failed build shows what went wrong, where, and the log`() = runBlocking<Unit> {
        val h = Harness(
            gradle = FakeGradle(
                exit = 1,
                lines = listOf(
                    "> Task :app:compileDebugKotlin FAILED",
                    "e: file:///work/app/src/main/kotlin/Main.kt:12:5 Unresolved reference 'pay'.",
                    "* What went wrong:",
                    "Execution failed for task ':app:compileDebugKotlin'.",
                    "* Try:",
                ),
            ),
        )
        val result = h.run()
        val f = result.failed(StepName.BUILD, ExitCode.BUILD)
        assertTrue("Unresolved reference 'pay'" in f.cause, f.cause)
        assertEquals(JsonPrimitive("/work/app/src/main/kotlin/Main.kt:12:5"), f.details!!["location"])
        assertTrue(result.log!! in f.next, "the next step points at the log: ${f.next}")
        assertTrue(h.device.started.isEmpty())
    }

    @Test
    fun `a build that injected nothing still runs, and says the app has no DTA and why`() = runBlocking<Unit> {
        val h = Harness(gradle = FakeGradle(report = selectedReport(InjectionOutcome.UNRESOLVED, "Could not find io.github.yamsergey:dta-sidekick:0.11.0")))
        val result = h.run()
        assertEquals(RunOutcome.RAN_WITHOUT_DTA, result.outcome)
        assertEquals(ExitCode.WITHOUT_DTA.code, result.exitCode)
        assertTrue(h.device.started.isNotEmpty(), "the app is launched anyway")
        assertFalse("watch" in h.daemon.calls, "no connection is awaited")
        val f = assertNotNull(result.failure)
        assertTrue("Could not find" in f.cause, f.cause)
    }

    @Test
    fun `a sidekick of another version is refused by the daemon - stated as a version conflict`() = runBlocking<Unit> {
        val h = Harness(daemon = FakeDaemon(outcomes = mutableListOf(ConnectionOutcome.Refused("the app's DTA is 0.9.0, this daemon is 0.11.0"))))
        val f = h.run().failed(StepName.CONNECT, ExitCode.VERSION_CONFLICT)
        assertTrue("0.9.0" in f.cause, f.cause)
        assertTrue("0.11.0" in f.next, f.next)
    }

    @Test
    fun `no device, several, or a missing one stop before building`() = runBlocking<Unit> {
        listOf(
            DeviceChoice.NoDevice to "emulator",
            DeviceChoice.Several(listOf("emulator-5554", "R5CX")) to "-d",
            DeviceChoice.NotFound("R5CX", listOf("emulator-5554")) to "emulator-5554",
        ).forEach { (choice, expected) ->
            val h = Harness(daemon = FakeDaemon(choice = choice))
            val f = h.run().failed(StepName.RESOLVE, ExitCode.NOT_AVAILABLE)
            assertTrue(expected in f.cause + " " + f.next, "$choice: $f")
            assertEquals(0, h.gradle.runs)
        }
    }

    @Test
    fun `an unreachable or conflicting daemon is stated`() = runBlocking<Unit> {
        Harness(daemon = FakeDaemon(unavailable = DaemonUnavailableException("The DTA daemon 0.9.0 is running; this is 0.11.0.", versionConflict = true)))
            .run().failed(StepName.RESOLVE, ExitCode.VERSION_CONFLICT)
        Harness(daemon = FakeDaemon(unavailable = DaemonUnavailableException("The DTA daemon did not start.")))
            .run().failed(StepName.RESOLVE, ExitCode.INTERNAL)
    }

    @Test
    fun `an install refused for the signature advises --reinstall and never uninstalls`() = runBlocking<Unit> {
        val h = Harness(
            gradle = FakeGradle(
                exit = 1,
                lines = listOf("> Task :app:installDebug FAILED", "com.android.ddmlib.InstallException: INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"),
            ),
        )
        val f = h.run().failed(StepName.BUILD, ExitCode.INSTALL)
        assertTrue("--reinstall" in f.next, f.next)
        assertEquals(JsonPrimitive("INSTALL_FAILED_UPDATE_INCOMPATIBLE"), f.details!!["code"])
        assertEquals(JsonPrimitive(false), h.gradle.request!!["reinstall"])
    }

    @Test
    fun `--reinstall is passed to the build`() = runBlocking<Unit> {
        val h = Harness()
        h.run(RunRequest(fakeProject(), reinstall = true))
        assertEquals(JsonPrimitive(true), h.gradle.request!!["reinstall"])
    }

    @Test
    fun `Gradle finding no device is stated as such`() = runBlocking<Unit> {
        val h = Harness(gradle = FakeGradle(exit = 1, lines = listOf("> Task :app:installDebug FAILED", "> com.android.builder.testing.api.DeviceException: No connected devices!")))
        h.run().failed(StepName.BUILD, ExitCode.NOT_AVAILABLE)
    }

    @Test
    fun `an app without a launcher activity points at --activity`() = runBlocking<Unit> {
        val f = Harness(device = FakeDevice(launcher = null)).run().failed(StepName.LAUNCH, ExitCode.LAUNCH)
        assertTrue("--activity" in f.next, f.next)
    }

    @Test
    fun `an activity that does not start says why`() = runBlocking<Unit> {
        val f = Harness(device = FakeDevice(launch = LaunchOutcome.Failed("Activity class {io.example.shop/io.example.shop.Nope} does not exist.")))
            .run(RunRequest(fakeProject(), activity = ".Nope")).failed(StepName.LAUNCH, ExitCode.LAUNCH)
        assertTrue("does not exist" in f.cause, f.cause)
    }

    @Test
    fun `a timeout says what was seen - the process, the sidekick`() = runBlocking<Unit> {
        // The process is gone: probably a crash at start.
        val crashed = Harness(device = FakeDevice(pids = mutableListOf(null, null)), daemon = FakeDaemon(outcomes = mutableListOf(ConnectionOutcome.TimedOut)))
        val f1 = crashed.run().failed(StepName.CONNECT, ExitCode.CONNECT)
        assertTrue("logcat" in f1.next, f1.next)
        assertEquals(JsonPrimitive(false), f1.details!!["processAlive"])

        // Running, no sidekick listening.
        val silent = Harness(device = FakeDevice(pids = mutableListOf(null, 7)), daemon = FakeDaemon(outcomes = mutableListOf(ConnectionOutcome.TimedOut), socket = false))
        val f2 = silent.run().failed(StepName.CONNECT, ExitCode.CONNECT)
        assertEquals(JsonPrimitive(true), f2.details!!["processAlive"])
        assertEquals(JsonPrimitive(false), f2.details!!["socketPresent"])

        // Listening, but not connected in time.
        val slow = Harness(device = FakeDevice(pids = mutableListOf(null, 7)), daemon = FakeDaemon(outcomes = mutableListOf(ConnectionOutcome.TimedOut), socket = true))
        val f3 = slow.run().failed(StepName.CONNECT, ExitCode.CONNECT)
        assertTrue("--timeout" in f3.next, f3.next)
    }

    @Test
    fun `build-only with an unresolved sidekick is not a success either`() = runBlocking<Unit> {
        val result = Harness(gradle = FakeGradle(report = selectedReport(InjectionOutcome.UNRESOLVED, "Could not find it")))
            .run(RunRequest(fakeProject(), mode = RunMode.BUILD_ONLY))
        assertEquals(RunOutcome.RAN_WITHOUT_DTA, result.outcome)
        assertEquals(ExitCode.WITHOUT_DTA.code, result.exitCode)
    }

    @Test
    fun `a build whose init script did not report is stated, not guessed`() = runBlocking<Unit> {
        Harness(gradle = FakeGradle(report = null)).run().failed(StepName.BUILD, ExitCode.PROJECT)
    }
}
