package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.SequencePage
import io.yamsergey.dta.protocol.timeline.SequenceStep
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 065: every text that hides rows says so; nothing is said when nothing was hidden. */
class TimelineSavedTextTest {
    private val words = listOf("-host:telemetry.example.test")

    @Test
    fun `the notice names the count, the filters, the rows not judged and the way out`() {
        assertEquals(
            "12 network rows hidden by Network's filters (-host:telemetry.example.test) · 1 not judged: its request is no longer kept · --no-saved-filters shows them",
            TimelineSavedText.notice(SavedFiltersApplied(words, hidden = 12, notJudged = 1)),
        )
        assertEquals(
            "1 network row hidden by Network's filters (status:5xx, -url:/health) · --no-saved-filters shows them",
            TimelineSavedText.notice(SavedFiltersApplied(listOf("status:5xx", "-url:/health"), hidden = 1)),
        )
        assertEquals(
            "1 network row hidden by Network's filters (status:5xx) · ignoreSavedFilters=true shows them",
            TimelineSavedText.notice(SavedFiltersApplied(listOf("status:5xx"), hidden = 1), optOut = "ignoreSavedFilters=true"),
        )
    }

    @Test
    fun `nothing hidden, the opt-out or no filters say nothing, a problem or no history is said`() {
        assertNull(TimelineSavedText.notice(null))
        assertNull(TimelineSavedText.notice(SavedFiltersApplied(words, hidden = 0)))
        assertNull(TimelineSavedText.notice(SavedFiltersApplied(words, ignored = true)))
        assertEquals("Network's filters (-host:telemetry.example.test) hid nothing here · 2 not judged: their requests are no longer kept",
            TimelineSavedText.notice(SavedFiltersApplied(words, hidden = 0, notJudged = 2)))
        assertEquals("Network's saved filters are not applied: no history here", TimelineSavedText.notice(SavedFiltersApplied(words, unavailable = "no history here")))
        assertEquals("Network's saved filters could not be read (bad json); nothing is hidden", TimelineSavedText.notice(SavedFiltersApplied(emptyList(), problem = "bad json")))
    }

    @Test
    fun `the sequence text ends with the notice`() {
        val page = SequencePage(listOf(SequenceStep(1_000, "user", "app", "tap \"Go\"")), 1, emptyList(), saved = SavedFiltersApplied(words, hidden = 3))
        assertTrue(SequenceText.lines(page).last().startsWith("3 network rows hidden by Network's filters"), SequenceText.lines(page).toString())
        assertTrue(SequenceText.lines(page.copy(saved = null)).none { "Network's filters" in it })
    }

    @Test
    fun `a cause chain marks the nodes the filters hide elsewhere`() {
        val tap = TimelineEvent("7:1", 1, 1, 1, Sources.INPUT, Kinds.CLICK)
        val req = TimelineEvent("7:2", 2, 2, 2, Sources.NETWORK, Kinds.NETWORK_REQUEST, network = NetworkPayload("e1", NetworkPhase.REQUEST, "GET", "telemetry.example.test", "/beat"),
            causes = listOf(CauseLink("7:1", Basis.EXACT, "issued-during:click")))
        val chain = CauseChain(tap, ChainDirection.EFFECTS, listOf(tap, req), listOf(ChainLink("7:1", "7:2", req.causes.single())),
            saved = SavedFiltersApplied(words, hidden = 1, hiddenIds = listOf("7:2")))
        val text = TimelineEventText.chain(chain)
        assertTrue(text.lines().single { it.contains("7:2") }.endsWith("· hidden by Network's filters"), text)
        assertTrue(text.lines().last().startsWith("1 network row hidden by Network's filters"), text)
    }
}
