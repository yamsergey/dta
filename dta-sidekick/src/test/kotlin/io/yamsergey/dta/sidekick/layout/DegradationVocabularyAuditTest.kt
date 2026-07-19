package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence
import io.yamsergey.dta.sidekick.layout.compose.EnrichmentOverlay
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SC-004 "zero silent degradations": every code in the vocabulary must be
 * PRODUCIBLE by real logic, not just declared. This audit exercises the
 * producers of each code deviceless (the two device-only producers,
 * window-lost and legacy-root under a real old API, are noted).
 */
class DegradationVocabularyAuditTest {

    @Test
    fun `tooling-absent and version-skew are produced by the presence gate`() {
        val absent = ComposePresence(
            classPresent = { it == ComposePresence.COMPOSE_UI_MARKER },
            readVersion = { "1.7.6" },
        )
        assertTrue(absent.tier2Skip == DegradationCodes.COMPOSE_TOOLING_ABSENT)

        val skew = ComposePresence(
            classPresent = { true },
            readVersion = { if (it == "compose-ui") "1.7.6" else "1.6.0" },
        )
        assertTrue(skew.tier2Skip == DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW)
    }

    @Test
    fun `enrichment-failed is produced when a tier-2 build throws`() {
        val overlay = EnrichmentOverlay.buildOrNull { throw RuntimeException("boom") }
        assertTrue(overlay == null, "null overlay is the signal the caller turns into the code")
    }

    @Test
    fun `first-frame-pending is produced by the merger for an unmatched host`() {
        val walk = ViewWalkResult(
            root = ViewNode(id = 1, bounds = Rect(0, 0, 1, 1), className = "V",
                children = listOf(ViewNode(id = 9, bounds = Rect(0, 0, 1, 1), className = "ComposeView"))),
            composeHostIds = setOf(9),
        )
        val merged = TreeMerger.merge(walk, emptyList())
        val host = merged.children.single() as ViewNode
        assertTrue(
            (host.children.single() as ComposeNode).degraded!!
                .any { it.reason == DegradationCodes.COMPOSE_FIRST_FRAME_PENDING },
        )
    }

    @Test
    fun `depth-cap is preserved by the merger`() {
        val capped = ViewNode(id = 1, bounds = Rect(0, 0, 1, 1), className = "V",
            degraded = listOf(DegradationMarker("layout", DegradationCodes.DEPTH_CAP_REACHED)))
        val merged = TreeMerger.merge(ViewWalkResult(capped), emptyList())
        assertTrue(merged.degraded!!.any { it.reason == DegradationCodes.DEPTH_CAP_REACHED })
    }

    @Test
    fun `legacy-root-discovery is produced by the root provider below api 29`() {
        assertTrue(
            RootProviderSelector.select(24).degradation?.reason == DegradationCodes.LEGACY_ROOT_DISCOVERY,
        )
    }

    @Test
    fun `every declared code is a non-blank stable constant`() {
        val all = listOf(
            DegradationCodes.COMPOSE_TOOLING_ABSENT,
            DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW,
            DegradationCodes.COMPOSE_ENRICHMENT_FAILED,
            DegradationCodes.COMPOSE_FIRST_FRAME_PENDING,
            DegradationCodes.DEPTH_CAP_REACHED,
            DegradationCodes.WINDOW_LOST_MID_CAPTURE, // produced in LayoutCaptureMethod (device)
            DegradationCodes.LEGACY_ROOT_DISCOVERY,
        )
        assertTrue(all.all { it.isNotBlank() } && all.toSet().size == all.size)
    }
}
