package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.network.Judgement
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelinePage
import io.yamsergey.dta.protocol.ui.ElementRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 065: timeline pages, cause chains and the live view's hidden set under Network's saved filters. */
class TimelineJudgingTest {
    private val tap = TimelineEvent("7:1", 1, 1_000, 1_000, Sources.INPUT, Kinds.CLICK, input = InputPayload(InputAction.CLICK, ElementRef("star"), null, via = "compose"))
    private fun net(s: Long, entry: String, phase: NetworkPhase, cause: String? = null) =
        TimelineEvent("7:$s", s, 1_000 + s, 1_000 + s, Sources.NETWORK, if (phase == NetworkPhase.REQUEST) Kinds.NETWORK_REQUEST else Kinds.NETWORK_RESPONSE,
            network = NetworkPayload(entry, phase, "GET", "h", "/$entry"),
            causes = listOfNotNull(cause?.let { CauseLink(it, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK) }))
    private val events = listOf(tap, net(2, "e1", NetworkPhase.REQUEST, "7:1"), net(3, "e1", NetworkPhase.RESPONSE), net(4, "e2", NetworkPhase.REQUEST), net(5, "gone", NetworkPhase.REQUEST))
    private val judgement = Judgement(listOf("-host:t.test"), passing = NetworkHistoryView.Passing(ids = setOf("e2"), seen = setOf("e1", "e2")))

    @Test
    fun `a page leaves hidden network events out, keeps its cursor and says how many`() {
        val page = TimelineJudging.page(TimelinePage(events, "c:5", more = false), judgement)
        assertEquals(listOf("7:1", "7:4", "7:5"), page.events.map { it.id })
        assertEquals("c:5", page.next)
        assertEquals(2, page.saved?.hidden)
        assertEquals(1, page.saved?.notJudged)
    }

    @Test
    fun `nothing saved leaves the page as it is`() {
        val page = TimelineJudging.page(TimelinePage(events, "c:5"), Judgement.NONE)
        assertEquals(events, page.events)
        assertNull(page.saved)
    }

    @Test
    fun `a cause chain keeps its hidden nodes and names them`() {
        val chain = CauseChain(tap, ChainDirection.EFFECTS, events.take(3), listOf(ChainLink("7:1", "7:2", events[1].causes.single())))
        val judged = TimelineJudging.chain(chain, judgement)
        assertEquals(chain.nodes, judged.nodes)
        assertEquals(listOf("7:2", "7:3"), judged.saved?.hiddenIds)
    }

    @Test
    fun `the live view gets the hidden and not judged entries of the timeline`() {
        val h = TimelineJudging.hidden(events, judgement)
        assertEquals(listOf("e1"), h.hidden)
        assertEquals(listOf("gone"), h.notJudged)
        assertEquals(listOf("-host:t.test"), h.saved?.words)
    }
}
