package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.layout.compose.CapabilityOutcome
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `layout` capability status MUST be computed from the SAME presence probes
 * the capture uses, so an identity report and a capture can never disagree
 * about enrichment availability (spec US2.4).
 */
class CapabilityCouplingTest {

    private val composeUi = ComposePresence.COMPOSE_UI_MARKER
    private val toolingData = ComposePresence.TOOLING_DATA_MARKER

    private fun status(present: Set<String>, uiV: String?, toolV: String?): CapabilityStatus =
        LayoutCapability.status(
            ComposePresence(
                classPresent = { it in present },
                readVersion = { if (it == "compose-ui") uiV else toolV },
            ),
        )

    @Test
    fun `views-only app reports available`() {
        assertEquals(CapabilityStatus.Status.AVAILABLE, status(emptySet(), null, null).status)
    }

    @Test
    fun `compose plus sane tooling reports available`() {
        assertEquals(
            CapabilityStatus.Status.AVAILABLE,
            status(setOf(composeUi, toolingData), "1.7.6", "1.7.6").status,
        )
    }

    @Test
    fun `tooling absent reports degraded with the same code the capture attaches`() {
        val s = status(setOf(composeUi), "1.7.6", null)
        assertEquals(CapabilityStatus.Status.DEGRADED, s.status)
        assertEquals(true, s.reason!!.contains(DegradationCodes.COMPOSE_TOOLING_ABSENT))
    }

    @Test
    fun `version skew reports degraded with the skew code`() {
        val s = status(setOf(composeUi, toolingData), "1.7.6", "1.6.0")
        assertEquals(CapabilityStatus.Status.DEGRADED, s.status)
        assertEquals(true, s.reason!!.contains(DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW))
    }

    @Test
    fun `outcome enum maps to capability status consistently`() {
        assertEquals(CapabilityOutcome.FULL, ComposePresence({ false }, { null }).capabilityOutcome())
    }
}
