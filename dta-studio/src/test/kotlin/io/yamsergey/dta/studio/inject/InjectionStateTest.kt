package io.yamsergey.dta.studio.inject

import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.inject.ProjectInjection
import io.yamsergey.dta.present.context.ContextEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import io.yamsergey.dta.present.context.InjectionOutcome as Shown

/** T044: what the context says about injection, from the switch and the last build's report. */
class InjectionStateTest {
    private fun report(vararg p: ProjectInjection) = InjectionReport("0.10.0", p.toList())

    @Test
    fun `off is off, whatever an old report says`() {
        assertEquals(ContextEvent.Injection(Shown.OFF, null), InjectionState.of(enabled = false, report = report(ProjectInjection(":app", InjectionOutcome.INJECTED)), built = true))
    }

    @Test
    fun `on, before any build, says nothing yet - after a build with no report, the script did not run`() {
        assertEquals(ContextEvent.Injection(null, null), InjectionState.of(enabled = true, report = null, built = false))
        assertEquals(ContextEvent.Injection(Shown.NOT_RUN, null), InjectionState.of(enabled = true, report = null, built = true))
    }

    @Test
    fun `one app project's outcome is the app's`() {
        val unresolved = report(ProjectInjection(":app", InjectionOutcome.UNRESOLVED, "Could not find x"))
        assertEquals(ContextEvent.Injection(Shown.UNRESOLVED, "Could not find x"), InjectionState.of(true, unresolved, built = true))
        assertEquals(Shown.PROJECT_PROVIDED, InjectionState.of(true, report(ProjectInjection(":app", InjectionOutcome.PROJECT_PROVIDED)), true).outcome)
    }

    @Test
    fun `with several app projects, a failure is shown with the project it belongs to`() {
        val mixed = report(ProjectInjection(":app", InjectionOutcome.INJECTED), ProjectInjection(":wear", InjectionOutcome.UNSUPPORTED, "boom"))
        assertEquals(ContextEvent.Injection(Shown.UNSUPPORTED, ":wear: boom"), InjectionState.of(true, mixed, built = true))
        val fine = report(ProjectInjection(":app", InjectionOutcome.INJECTED), ProjectInjection(":wear", InjectionOutcome.INJECTED))
        assertEquals(ContextEvent.Injection(Shown.INJECTED, null), InjectionState.of(true, fine, built = true))
    }
}
