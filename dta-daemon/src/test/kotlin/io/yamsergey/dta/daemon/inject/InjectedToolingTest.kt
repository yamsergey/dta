package io.yamsergey.dta.daemon.inject

import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.inject.ProjectInjection
import io.yamsergey.dta.inject.ToolingInjection
import io.yamsergey.dta.inject.ToolingOutcome
import io.yamsergey.dta.inject.VariantInfo
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.ToolingSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T034 (US6): the layout capability says where Compose's tooling data came from. */
class InjectedToolingTest {
    private val home: File = Files.createTempDirectory("dta-home").toFile()
    private val pkg = "io.example.shop"

    private fun identity(levels: List<DetailLevel>) = IdentityReport(
        1, "0.11.0", pkg,
        listOf(
            CapabilityStatus("layout", CapabilityStatus.Status.AVAILABLE, levels = levels),
            CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE),
        ),
    )

    private fun report(tooling: ToolingOutcome, version: String? = "1.11.4") {
        val r = InjectionReport(
            "0.11.0",
            listOf(ProjectInjection(":app", InjectionOutcome.INJECTED, tooling = ToolingInjection(tooling, version), variants = listOf(VariantInfo("debug", "debug", debuggable = true, applicationId = pkg)))),
        )
        File(home, "injection/$pkg.json").apply { parentFile.mkdirs() }.writeText(r.toJson())
    }

    private fun layout(report: IdentityReport) = report.capabilities.first { it.name == "layout" }

    @Test
    fun `tooling data DTA added is stated, with its version`() {
        report(ToolingOutcome.INJECTED)
        val annotated = InjectedTooling(home).annotate(identity(DetailLevel.entries.toList()))
        assertEquals(ToolingSource(present = true, source = "dta", version = "1.11.4"), layout(annotated).tooling)
        assertNull(annotated.capabilities.first { it.name == "network" }.tooling)
    }

    @Test
    fun `added but not usable says so`() {
        report(ToolingOutcome.INJECTED)
        assertEquals(false, layout(InjectedTooling(home).annotate(identity(listOf(DetailLevel.SEMANTIC)))).tooling!!.present)
    }

    @Test
    fun `the project's own tooling is stated as the project's`() {
        report(ToolingOutcome.PROJECT_PROVIDED, null)
        assertEquals(ToolingSource(present = true, source = "project"), layout(InjectedTooling(home).annotate(identity(DetailLevel.entries.toList()))).tooling)
    }

    @Test
    fun `nothing is claimed without a report, or when DTA added nothing`() {
        assertNull(layout(InjectedTooling(home).annotate(identity(DetailLevel.entries.toList()))).tooling)
        report(ToolingOutcome.NOT_APPLICABLE, null)
        assertNull(layout(InjectedTooling(home).annotate(identity(DetailLevel.entries.toList()))).tooling)
        File(home, "injection/$pkg.json").writeText("not json")
        assertNull(layout(InjectedTooling(home).annotate(identity(DetailLevel.entries.toList()))).tooling, "an unreadable report claims nothing")
    }
}
