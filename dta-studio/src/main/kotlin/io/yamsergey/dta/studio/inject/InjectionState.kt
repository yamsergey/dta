package io.yamsergey.dta.studio.inject

import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.present.context.ContextEvent
import io.yamsergey.dta.inject.InjectionOutcome as Reported
import io.yamsergey.dta.present.context.InjectionOutcome as Shown

/** What the context says about injection, from the switch and the last build's report (T044). */
object InjectionState {
    /**
     * [built]: a build finished since the plugin started, so a missing report means the build
     * did not run the init script. With several app projects, one that failed is shown, named.
     */
    fun of(enabled: Boolean, report: InjectionReport?, built: Boolean): ContextEvent.Injection {
        if (!enabled) return ContextEvent.Injection(Shown.OFF, null)
        if (report == null) return ContextEvent.Injection(if (built) Shown.NOT_RUN else null, null)
        val projects = report.projects
        val failed = projects.firstOrNull { it.outcome == Reported.UNRESOLVED || it.outcome == Reported.UNSUPPORTED }
        val shown = failed ?: projects.firstOrNull() ?: return ContextEvent.Injection(Shown.NOT_RUN, null)
        val reason = shown.reason?.let { if (projects.size > 1) "${shown.path}: $it" else it }
        return ContextEvent.Injection(shown.outcome.shown(), reason)
    }

    private fun Reported.shown(): Shown = when (this) {
        Reported.INJECTED -> Shown.INJECTED
        Reported.PROJECT_PROVIDED -> Shown.PROJECT_PROVIDED
        Reported.UNRESOLVED -> Shown.UNRESOLVED
        Reported.UNSUPPORTED -> Shown.UNSUPPORTED
    }
}
