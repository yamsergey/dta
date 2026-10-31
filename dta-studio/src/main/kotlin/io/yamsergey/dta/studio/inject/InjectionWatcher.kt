package io.yamsergey.dta.studio.inject

import com.android.tools.idea.gradle.project.build.BuildContext
import com.android.tools.idea.gradle.project.build.BuildStatus
import com.android.tools.idea.gradle.project.build.GradleBuildListener
import com.android.tools.idea.gradle.project.build.GradleBuildState
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.yamsergey.dta.client.DtaHome
import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.studio.ContextController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the context's injection state current (T044): at start, when the switch changes, and
 * after every build Android Studio runs (Run included), from the project's report file.
 */
class InjectionWatcher(private val project: Project, private val context: ContextController, scope: CoroutineScope, parent: Disposable) {
    @Volatile
    private var built = false

    init {
        GradleBuildState.subscribe(project, object : GradleBuildListener {
            override fun buildStarted(context: BuildContext) = Unit
            override fun buildFinished(status: BuildStatus, context: BuildContext) {
                built = true
                refresh()
            }
        }, parent)
        scope.launch { InjectionSettings.get().enabledFlow.collect { refresh() } }
    }

    fun refresh() {
        val enabled = InjectionSettings.get().enabled
        val file = InjectionFiles.report(project)
        val report = if (!enabled || !file.isFile) null else try {
            InjectionReport.parse(file.readText())
        } catch (e: Exception) {
            // Said as it is, never taken for "the script did not run".
            LOG.warn("DTA injection: unreadable report ${file.path}", e)
            return context.apply(io.yamsergey.dta.present.context.ContextEvent.Injection(
                io.yamsergey.dta.present.context.InjectionOutcome.UNSUPPORTED, "the injection report ${file.path} could not be read: ${e.message}"))
        }
        // Where the daemon reads what DTA added to each app (010 research R6).
        report?.let { r -> runCatching { r.keep(DtaHome.resolve().root) } }
        context.apply(InjectionState.of(enabled, report, built))
    }

    private companion object {
        val LOG = logger<InjectionWatcher>()
    }
}
