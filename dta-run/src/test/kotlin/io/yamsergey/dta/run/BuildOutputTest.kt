package io.yamsergey.dta.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T013: Gradle's plain console output as the run reads it (research R3, R8). */
class BuildOutputTest {

    @Test
    fun `task lines give the phase`() {
        assertEquals(Phase.COMPILE, BuildOutput.phaseOf("> Task :app:compileDebugKotlin"))
        assertEquals(Phase.COMPILE, BuildOutput.phaseOf("> Task :app:mergeDebugResources UP-TO-DATE"))
        assertEquals(Phase.PACKAGE, BuildOutput.phaseOf("> Task :app:packageDebug"))
        assertEquals(Phase.PACKAGE, BuildOutput.phaseOf("> Task :app:assembleDebug"))
        assertEquals(Phase.INSTALL, BuildOutput.phaseOf("> Task :app:installDebug"))
        assertEquals(Phase.INSTALL, BuildOutput.phaseOf("> Task :app:uninstallDebug"))
        assertNull(BuildOutput.phaseOf("> Configure project :app"))
        assertNull(BuildOutput.phaseOf("[DTA] :app: injected"))
        assertNull(BuildOutput.phaseOf("> Task :dtaRun"), "DTA's own task is not a phase")
    }

    @Test
    fun `phases only move forward`() {
        val tracker = BuildOutput.PhaseTracker(clock = sequenceOf(0L, 100L, 400L, 900L, 1000L).iterator()::next)
        tracker.line("> Task :app:preBuild")
        tracker.line("> Task :app:packageDebug")
        tracker.line("> Task :app:compileDebugKotlin") // late: stays in package
        tracker.line("> Task :app:installDebug")
        assertEquals(mapOf(Phase.CONFIGURE to 100L, Phase.COMPILE to 300L, Phase.PACKAGE to 500L, Phase.INSTALL to 100L), tracker.finish())
    }

    @Test
    fun `the failure summary is what went wrong, and the first compiler error is its location`() {
        val output = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///work/shop/app/src/main/kotlin/io/example/Main.kt:12:5 Unresolved reference 'pay'.
            e: file:///work/shop/app/src/main/kotlin/io/example/Main.kt:20:1 Unresolved reference 'x'.

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:compileDebugKotlin'.
            > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers${'$'}GradleKotlinCompilerWorkAction
               > Compilation error. See log for more details

            * Try:
            > Run with --stacktrace option to get the stack trace.
        """.trimIndent().lines()
        val summary = BuildOutput.failure(output)
        assertTrue(summary.summary.startsWith("Execution failed for task ':app:compileDebugKotlin'."), summary.summary)
        assertTrue("Compilation error" in summary.summary)
        assertEquals("/work/shop/app/src/main/kotlin/io/example/Main.kt:12:5", summary.location)
        assertEquals("Unresolved reference 'pay'.", summary.message)
    }

    @Test
    fun `a javac error is a location too`() {
        val summary = BuildOutput.failure(listOf("/work/shop/app/src/main/java/A.java:7: error: ';' expected", "* What went wrong:", "Execution failed for task ':app:compileDebugJavaWithJavac'."))
        assertEquals("/work/shop/app/src/main/java/A.java:7", summary.location)
        assertEquals("';' expected", summary.message)
    }

    @Test
    fun `install failures are read by their package manager code`() {
        val lines = listOf(
            "> Task :app:installDebug FAILED",
            "Installing APK 'app-debug.apk' on 'Pixel_8(AVD) - 16' for :app:debug",
            "Unable to install /work/app-debug.apk",
            "com.android.ddmlib.InstallException: INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package io.example signatures do not match newer version; ignoring!",
        )
        val install = BuildOutput.installFailure(lines)!!
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", install.code)
        assertTrue("--reinstall" in install.next, install.next)
        assertTrue("different signature" in install.cause, install.cause)
    }

    @Test
    fun `every known install code has its own cause, and an unknown one is named`() {
        listOf(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_VERSION_DOWNGRADE", "INSTALL_FAILED_OLDER_SDK",
            "INSTALL_FAILED_INSUFFICIENT_STORAGE", "INSTALL_FAILED_USER_RESTRICTED", "INSTALL_FAILED_NO_MATCHING_ABIS",
        ).forEach { code ->
            val f = BuildOutput.installFailure(listOf("…: $code: details"))!!
            assertEquals(code, f.code)
            assertTrue(code !in f.cause, "$code has a readable cause, not the code: ${f.cause}")
        }
        val unknown = BuildOutput.installFailure(listOf("INSTALL_FAILED_SOMETHING_NEW: x"))!!
        assertTrue("INSTALL_FAILED_SOMETHING_NEW" in unknown.cause)
    }

    @Test
    fun `no connected device in Gradle's own words is recognised`() {
        assertTrue(BuildOutput.noDevice(listOf("Execution failed for task ':app:installDebug'.", "> com.android.builder.testing.api.DeviceException: No connected devices!")))
        assertTrue(!BuildOutput.noDevice(listOf("BUILD SUCCESSFUL")))
    }
}
