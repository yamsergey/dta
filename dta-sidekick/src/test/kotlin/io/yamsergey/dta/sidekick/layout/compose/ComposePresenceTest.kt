package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.DegradationCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Presence/version gating with injectable class-lookup + version-reader so the
 * whole decision matrix is deviceless. The real gatekeeper touches NO compose
 * classes itself (constitution I) — it only asks whether they exist.
 */
class ComposePresenceTest {

    private fun presence(
        classesPresent: Set<String>,
        composeUiVersion: String? = null,
        toolingVersion: String? = null,
    ) = ComposePresence(
        classPresent = { it in classesPresent },
        readVersion = { artifact ->
            when (artifact) {
                "compose-ui" -> composeUiVersion
                "ui-tooling-data" -> toolingVersion
                else -> null
            }
        },
    )

    private val composeUi = ComposePresence.COMPOSE_UI_MARKER
    private val toolingData = ComposePresence.TOOLING_DATA_MARKER

    @Test
    fun `no compose in the app means tier 1 unavailable and nothing probed further`() {
        val p = presence(classesPresent = emptySet())
        assertTrue(!p.composePresent)
        assertTrue(!p.tier2Available)
        assertNull(p.tier2Skip)
    }

    @Test
    fun `compose present but tooling absent gives tier 1 only with tooling-absent code`() {
        val p = presence(classesPresent = setOf(composeUi), composeUiVersion = "1.7.6")
        assertTrue(p.composePresent)
        assertTrue(!p.tier2Available)
        assertEquals(DegradationCodes.COMPOSE_TOOLING_ABSENT, p.tier2Skip)
    }

    @Test
    fun `both present with equal versions enables tier 2`() {
        val p = presence(
            classesPresent = setOf(composeUi, toolingData),
            composeUiVersion = "1.7.6",
            toolingVersion = "1.7.6",
        )
        assertTrue(p.tier2Available)
        assertNull(p.tier2Skip)
    }

    @Test
    fun `version mismatch disables tier 2 with version-skew code`() {
        val p = presence(
            classesPresent = setOf(composeUi, toolingData),
            composeUiVersion = "1.7.6",
            toolingVersion = "1.6.0",
        )
        assertTrue(!p.tier2Available)
        assertEquals(DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW, p.tier2Skip)
    }

    @Test
    fun `unreadable version files disable tier 2 with skew code`() {
        val p = presence(
            classesPresent = setOf(composeUi, toolingData),
            composeUiVersion = null,
            toolingVersion = null,
        )
        assertTrue(!p.tier2Available)
        assertEquals(DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW, p.tier2Skip)
    }

    @Test
    fun `capability status reflects the gate outcome`() {
        assertEquals(
            CapabilityOutcome.FULL,
            presence(setOf(composeUi, toolingData), "1.7.6", "1.7.6").capabilityOutcome(),
        )
        assertEquals(
            CapabilityOutcome.FULL,
            presence(emptySet()).capabilityOutcome(), // views-only app is full fidelity for what exists
        )
        assertEquals(
            CapabilityOutcome.DEGRADED,
            presence(setOf(composeUi), "1.7.6").capabilityOutcome(),
        )
    }
}
