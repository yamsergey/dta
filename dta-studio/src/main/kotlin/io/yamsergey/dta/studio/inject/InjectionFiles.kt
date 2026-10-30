package io.yamsergey.dta.studio.inject

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import io.yamsergey.dta.inject.InitScript
import io.yamsergey.dta.inject.InjectionConfig
import io.yamsergey.dta.studio.DtaApplicationService
import java.io.File

/**
 * Where injection keeps its files: the init script, written once per plugin version, and one
 * report file per project. The report path stays the same from build to build, since a changed
 * `-P` value voids Gradle's configuration cache.
 */
object InjectionFiles {
    private val root: File get() = File(PathManager.getSystemPath(), "dta/inject")

    /** The init script for this plugin's release, written when missing or different. */
    fun script(): File {
        val config = config(pluginVersion())
        val file = File(root, "dta-sidekick-${config.version}.gradle")
        val text = InitScript.render(config)
        if (!file.isFile || file.readText() != text) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        return file
    }

    fun report(project: Project): File = File(root, "reports/${project.locationHash}.json")

    fun config(pluginVersion: String): InjectionConfig = InjectionConfig.forRelease(pluginVersion)

    /** The plugin's version: the one release version the plugin, daemon and sidekick share (FR-025). */
    private fun pluginVersion(): String = DtaApplicationService.get().version.version
}
