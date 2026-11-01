package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.timeline.CausesFixture.click
import io.yamsergey.dta.daemon.timeline.CausesFixture.dialog
import io.yamsergey.dta.daemon.timeline.CausesFixture.dialogClosed
import io.yamsergey.dta.daemon.timeline.CausesFixture.flow
import io.yamsergey.dta.daemon.timeline.CausesFixture.id
import io.yamsergey.dta.daemon.timeline.CausesFixture.removed
import io.yamsergey.dta.daemon.timeline.CausesFixture.request
import io.yamsergey.dta.daemon.timeline.CausesFixture.response
import io.yamsergey.dta.daemon.timeline.CausesFixture.toast
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.CutReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Feature 016 (T025): causes backwards and effects forwards, with cuts stated. */
class CauseGraphTest {
    private val graph = CauseGraph(flow)

    @Test
    fun `backwards from the response reaches the click through exact links`() {
        val chain = graph.walk(response.id, ChainDirection.CAUSES)
        assertEquals(listOf(response.id, request.id, click.id), chain.nodes.map { it.id })
        assertTrue(chain.links.all { it.link.basis == Basis.EXACT })
        assertEquals(emptyList(), chain.noKnownCause)
    }

    @Test
    fun `a toast without exact or declared cause says so and counts the inferred candidates`() {
        val chain = graph.walk(toast.id, ChainDirection.CAUSES)
        assertEquals(listOf(toast.id), chain.nodes.map { it.id })
        assertEquals(listOf(toast.id), chain.noKnownCause)
        assertTrue((chain.inferredCandidates[toast.id] ?: 0) >= 1)
    }

    @Test
    fun `with inferred links the toast's chain reaches the click, the inferred link marked`() {
        val chain = graph.walk(toast.id, ChainDirection.CAUSES, inferred = true)
        assertEquals(click.id, chain.nodes.last().id)
        val first = chain.links.first { it.to == toast.id }
        assertEquals(Basis.INFERRED, first.link.basis)
        assertEquals(response.id, first.from)
        assertTrue(chain.inferredIncluded)
    }

    @Test
    fun `forwards from the click gives everything that followed from it`() {
        val chain = graph.walk(click.id, ChainDirection.EFFECTS)
        assertEquals(setOf(click.id, request.id, dialog.id, response.id, dialogClosed.id, removed.id), chain.nodes.map { it.id }.toSet())
    }

    @Test
    fun `depth is a limit, stated as a cut`() {
        val chain = graph.walk(response.id, ChainDirection.CAUSES, depth = 1)
        assertEquals(listOf(response.id, request.id), chain.nodes.map { it.id })
        assertEquals(CutReason.DEPTH, chain.cuts.single().reason)
    }

    @Test
    fun `an unknown far end and an evicted one are cuts`() {
        val unknown = toast.copy(causes = listOf(CauseLink("local:3", Basis.DECLARED, CauseRules.APP_DECLARED, fromUnknown = true)))
        val evicted = toast.copy(id = id(9), seq = 9, causes = listOf(CauseLink(id(0), Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)))
        val g = CauseGraph(flow.drop(1).map { if (it.id == toast.id) unknown else it } + evicted)
        assertEquals(CutReason.UNKNOWN_EVENT, g.walk(toast.id, ChainDirection.CAUSES).cuts.single().reason)
        assertEquals(CutReason.EVICTED, g.walk(evicted.id, ChainDirection.CAUSES).cuts.single().reason)
    }

    @Test
    fun `links never cross a process`() {
        val other = toast.copy(id = "8:1", seq = 1, causes = listOf(CauseLink(click.id, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)))
        val chain = CauseGraph(flow + other).walk(other.id, ChainDirection.CAUSES)
        assertEquals(CutReason.PROCESS_BOUNDARY, chain.cuts.single().reason)
    }

    @Test
    fun `a declared link that points forward in time is rejected and stated`() {
        val forward = click.copy(causes = listOf(CauseLink(removed.id, Basis.DECLARED, CauseRules.APP_DECLARED)))
        val chain = CauseGraph(listOf(forward) + flow.drop(1)).walk(click.id, ChainDirection.CAUSES)
        assertEquals(listOf(click.id), chain.nodes.map { it.id })
        assertTrue(chain.root.completeness.any { it.reason.startsWith("timeline-rejected-cause") })
    }

    @Test
    fun `clicks are roots, not events with an unknown cause`() {
        assertEquals(emptyList(), graph.walk(click.id, ChainDirection.CAUSES).noKnownCause)
    }

    @Test
    fun `an id that is not kept is an error naming whether it was evicted`() {
        val e = assertFailsWith<CauseGraph.UnknownEvent> { CauseGraph(flow.drop(2)).walk(id(1), ChainDirection.CAUSES) }
        assertTrue(e.evicted)
    }
}
