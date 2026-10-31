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

    @Test
    fun `the v2 report carries tooling, variants and the run selection`() {
        val v2 = InjectionReport.parse("""
            {"version":"0.11.0","projects":[{"path":":app","outcome":"injected","reason":null,
              "tooling":{"outcome":"injected","version":"1.10.2","reason":null},
              "variants":[{"name":"debug","buildType":"debug","flavors":[],"debuggable":true,"applicationId":"io.example.shop"},
                          {"name":"release","buildType":"release","flavors":[],"debuggable":false,"applicationId":"io.example.shop"}]}],
             "run":{"outcome":"selected","module":":app","variant":"debug","task":":app:installDebug","applicationId":"io.example.shop","choices":null,"reason":null}}
        """)
        val app = v2.projects.single()
        assertEquals(ToolingOutcome.INJECTED, app.tooling!!.outcome)
        assertEquals("1.10.2", app.tooling!!.version)
        assertEquals(listOf(true, false), app.variants.map { it.debuggable })
        assertEquals("io.example.shop", app.variants.first().applicationId)
        assertEquals(RunOutcome.SELECTED, v2.run!!.outcome)
        assertEquals(":app:installDebug", v2.run!!.task)
        assertEquals(v2, InjectionReport.parse(v2.toJson()))
    }

    @Test
    fun `every tooling and run outcome of the contract reads`() {
        listOf("injected", "project-provided", "not-applicable", "unresolved", "off").forEach { o ->
            InjectionReport.parse("""{"version":"1","projects":[{"path":":a","outcome":"injected","tooling":{"outcome":"$o"}}]}""")
        }
        listOf("selected", "no-app-module", "module-required", "module-not-found", "variant-required", "variant-not-found", "not-debuggable").forEach { o ->
            val run = InjectionReport.parse("""{"version":"1","projects":[],"run":{"outcome":"$o","choices":[":a",":b"]}}""").run!!
            assertEquals(listOf(":a", ":b"), run.choices)
        }
    }

    @Test
    fun `a 009 report without the v2 fields still reads`() {
        val old = InjectionReport.parse(json)
        assertEquals(null, old.run)
        assertEquals(null, old.projects.first().tooling)
        assertEquals(emptyList(), old.projects.first().variants)
    }

    @Test
    fun `a report is kept under the home once per application id`() {
        val home = java.nio.file.Files.createTempDirectory("dta-home").toFile()
        val report = InjectionReport(
            "1", listOf(ProjectInjection(":app", InjectionOutcome.INJECTED, variants = listOf(
                VariantInfo("debug", "debug", debuggable = true, applicationId = "io.example.shop.debug"),
                VariantInfo("staging", "staging", debuggable = true, applicationId = "io.example.shop.debug"),
                VariantInfo("release", "release", debuggable = false, applicationId = "io.example.shop"),
            ))),
        )
        assertEquals(setOf("io.example.shop.debug", "io.example.shop"), report.keep(home).map { it.nameWithoutExtension }.toSet())
        assertEquals(report, InjectionReport.parse(java.io.File(home, "injection/io.example.shop.debug.json").readText()))
    }
}
