package io.yamsergey.dta.daemon.inject

import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.inject.ToolingOutcome
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.ToolingSource
import java.io.File

/**
 * Where an app's Compose tooling data came from (feature 010 US6, research R6): the sidekick can
 * only say whether it is there; the last injection report of the app — written by `dta run`
 * and the IDE under `<home>/injection/<applicationId>.json` — says whether DTA added it.
 */
class InjectedTooling(private val home: File) {

    fun annotate(report: IdentityReport): IdentityReport {
        val source = source(report.packageName) ?: return report
        // Tooling is usable when the layout serves more than the semantic level (feature 003).
        return report.copy(
            capabilities = report.capabilities.map { c ->
                if (c.name != LAYOUT) c else c.copy(tooling = source.copy(present = c.levels.orEmpty().any { it != DetailLevel.SEMANTIC }))
            },
        )
    }

    private fun source(pkg: String): ToolingSource? {
        val file = File(home, "injection/$pkg.json").takeIf { it.isFile } ?: return null
        val injection = runCatching { InjectionReport.parse(file.readText()) }.getOrNull() ?: return null
        val tooling = injection.projects.firstOrNull { p -> p.variants.any { it.applicationId == pkg } }?.tooling ?: return null
        return when (tooling.outcome) {
            ToolingOutcome.INJECTED -> ToolingSource(present = false, source = "dta", version = tooling.version)
            ToolingOutcome.PROJECT_PROVIDED -> ToolingSource(present = false, source = "project")
            else -> null
        }
    }

    private companion object {
        const val LAYOUT = "layout"
    }
}
