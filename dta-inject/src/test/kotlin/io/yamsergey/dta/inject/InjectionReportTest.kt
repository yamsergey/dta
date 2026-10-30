package io.yamsergey.dta.inject

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** T039: the report of contracts/injection.md. */
class InjectionReportTest {
    private val json = """
        {"version":"0.1.0","projects":[
          {"path":":app","outcome":"injected","reason":null},
          {"path":":wear","outcome":"unresolved","reason":"Could not find io.github.yamsergey:dta-sidekick:0.1.0 (searched: Maven Central)"}]}
    """

    @Test
    fun `the contract's example reads and writes back`() {
        val report = InjectionReport.parse(json)
        assertEquals("0.1.0", report.version)
        assertEquals(listOf(InjectionOutcome.INJECTED, InjectionOutcome.UNRESOLVED), report.projects.map { it.outcome })
        assertEquals(report, InjectionReport.parse(report.toJson()))
        assertEquals(InjectionOutcome.PROJECT_PROVIDED, InjectionReport.parse("""{"version":"1","projects":[{"path":":a","outcome":"project-provided"}]}""").projects.single().outcome)
    }

    @Test
    fun `an unknown outcome is refused, not guessed`() {
        assertFailsWith<IllegalArgumentException> { InjectionReport.parse("""{"version":"1","projects":[{"path":":a","outcome":"maybe"}]}""") }
    }
}
