package io.yamsergey.dta.studio.inject

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.inject.InjectionConfig
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

/** T042/T043: the switch persists; the init script is added to IDE builds only when it is on. */
class SidekickInjectorTest : BasePlatformTestCase() {

    private fun configured(): List<String> {
        val settings = GradleExecutionSettings()
        val id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
        SidekickInjector().configureTasks(project.basePath!!, id, settings, null)
        return settings.arguments
    }

    override fun tearDown() {
        try {
            InjectionSettings.get().enabled = true
            InjectionSettings.get().tooling = true
        } finally {
            super.tearDown()
        }
    }

    fun `test on by default, the init script and the project's report file are passed`() {
        assertTrue(InjectionSettings.get().enabled)
        val args = configured()
        val script = File(args[args.indexOf("--init-script") + 1])
        assertTrue(script.path, script.isFile && "dta-sidekick" in script.readText())
        val report = args.single { it.startsWith("-P${InjectionConfig.REPORT_PROPERTY}=") }.substringAfter('=')
        assertEquals(InjectionFiles.report(project).path, report)
        assertEquals("the same file every build: a changed path would void Gradle's configuration cache", report, configured().single { it.startsWith("-P") }.substringAfter('='))
    }

    fun `test Compose tooling data is on by default and can be turned off for IDE builds`() {
        assertTrue(InjectionSettings.get().tooling)
        assertTrue(configured().none { it.startsWith("-P${InjectionConfig.TOOLING_PROPERTY}=") })
        InjectionSettings.get().tooling = false
        assertTrue("-P${InjectionConfig.TOOLING_PROPERTY}=false" in configured())
        val restored = InjectionSettings().apply { loadState(InjectionSettings.get().state) }
        assertFalse(restored.tooling)
    }

    fun `test off adds nothing`() {
        InjectionSettings.get().enabled = false
        assertEquals(emptyList<String>(), configured())
    }

    fun `test the switch survives a restart`() {
        val settings = InjectionSettings()
        settings.enabled = false
        val restored = InjectionSettings().apply { loadState(settings.state) }
        assertFalse(restored.enabled)
    }

    fun `test the script injects this plugin's release, an EAP run number dropped`() {
        assertEquals("0.10.0-SNAPSHOT", InjectionFiles.config("0.10.0-SNAPSHOT.7").version)
    }
}
