package io.yamsergey.dta.studio.inject

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import io.yamsergey.dta.inject.InjectionConfig
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings

/**
 * Adds DTA to the debug builds the IDE runs, Run and Debug included (research R10): every
 * Gradle invocation gets `--init-script` and the project's report file while injection is on.
 * No project file changes; the script itself never fails a build and touches only debug.
 */
class SidekickInjector : GradleTaskManagerExtension {
    override fun configureTasks(projectPath: String, id: ExternalSystemTaskId, settings: GradleExecutionSettings, gradleVersion: GradleVersion?) {
        if (!InjectionSettings.get().enabled) return
        val project = id.findProject() ?: return
        try {
            settings.withArguments("--init-script", InjectionFiles.script().path, "-P${InjectionConfig.REPORT_PROPERTY}=${InjectionFiles.report(project).path}")
        } catch (e: Exception) {
            // Without the script the build runs as it would without DTA; the report's absence says so.
            LOG.warn("DTA injection: could not prepare the init script", e)
        }
    }

    private companion object {
        val LOG = logger<SidekickInjector>()
    }
}
