package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelineRead
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Features 015/016 (T011): the host's timeline of one app, read with cursors and filters. */
class TimelineTest {
    private var now = 1_000L
    private val timeline = Timeline(session = "s1", bound = 5, clock = { now })

    private fun ev(epoch: Long, seq: Long, source: String = Sources.UI, kind: String = Kinds.ui("toast")) =
        TimelineEvent("$epoch:$seq", seq, monoMs = seq, wallMs = 10_000 + seq, source = source, kind = kind, title = "t$seq")

    private fun read(epoch: Long, vararg seqs: Long, dropped: Int = 0) =
        TimelineRead(epoch, seqs.map { ev(epoch, it) }, dropped, lastSeq = seqs.maxOrNull() ?: 0)

    private fun titles(page: List<TimelineEvent>) = page.map { it.title }

    @Test
    fun `catch-up then live pushes, read in order with a continuing cursor`() {
        timeline.ingest(read(7, 1, 2))
        assertTrue(timeline.pushed(ev(7, 3)))
        val first = timeline.page(since = null, limit = 2)
        assertEquals(listOf("t1", "t2"), titles(first.events))
        assertTrue(first.more)
        val second = timeline.page(since = first.next, limit = 10)
        assertEquals(listOf("t3"), titles(second.events))
        assertFalse(second.more)
        assertEquals(emptyList(), timeline.page(since = second.next, limit = 10).events)
    }

    @Test
    fun `a push out of order asks for a catch-up and is not stored`() {
        timeline.ingest(read(7, 1))
        assertFalse(timeline.pushed(ev(7, 3)))
        timeline.ingest(read(7, 2, 3))
        assertEquals(listOf("t1", "t2", "t3"), titles(timeline.page(null, 10).events))
    }

    @Test
    fun `events already stored are not duplicated`() {
        timeline.ingest(read(7, 1, 2))
        timeline.ingest(read(7, 2, 3))
        assertFalse(timeline.pushed(ev(7, 2)))
        assertEquals(listOf("t1", "t2", "t3"), titles(timeline.page(null, 10).events))
    }

    @Test
    fun `events the app's ring lost become a gap`() {
        timeline.ingest(read(7, 1))
        timeline.ingest(read(7, 5, dropped = 3))
        val events = timeline.page(null, 10).events
        assertEquals(listOf(Kinds.ui("toast"), Kinds.GAP, Kinds.ui("toast")), events.map { it.kind })
        assertEquals(3, events[1].gapCount)
        assertTrue(events[1].completeness.single().reason.startsWith("ui-events-dropped"))
    }

    @Test
    fun `a new process appends the end of the old one at the moment its link was lost`() {
        timeline.ingest(read(7, 1))
        now = 2_000
        timeline.connectionLost()
        now = 9_000
        timeline.ingest(read(8, 1))
        val events = timeline.page(null, 10).events
        assertEquals(listOf(Kinds.ui("toast"), Kinds.PROCESS_ENDED, Kinds.ui("toast")), events.map { it.kind })
        assertEquals(2_000, events[1].wallMs)
        assertEquals(Sources.TIMELINE, events[1].source)
    }

    @Test
    fun `a process that ended is recorded once`() {
        timeline.ingest(read(7, 1))
        timeline.connectionLost()
        timeline.processEnded()
        timeline.processEnded()
        timeline.ingest(read(8, 1))
        assertEquals(1, timeline.page(null, 10).events.count { it.kind == Kinds.PROCESS_ENDED })
    }

    @Test
    fun `beyond the bound the oldest go, and a reader behind them is told`() {
        val behind = timeline.page(null, 1).next
        timeline.ingest(read(7, 1, 2, 3, 4, 5, 6, 7))
        val page = timeline.page(behind, 10)
        assertEquals(Kinds.GAP, page.events.first().kind)
        assertTrue(page.events.first().completeness.single().reason.startsWith("ui-history-evicted"))
        assertEquals(listOf("t3", "t4", "t5", "t6", "t7"), titles(page.events.drop(1)))
    }

    @Test
    fun `a cursor of another timeline reads from the oldest, saying so`() {
        timeline.ingest(read(7, 1))
        val page = timeline.page("other:3", 10)
        assertEquals(Kinds.GAP, page.events.first().kind)
        assertEquals("t1", page.events[1].title)
    }

    @Test
    fun `filters by source, kind and time, and gaps and process ends always come`() {
        timeline.ingest(TimelineRead(7, listOf(ev(7, 1), ev(7, 2, Sources.INPUT, Kinds.CLICK), ev(7, 3, Sources.NETWORK, Kinds.NETWORK_REQUEST)), lastSeq = 3))
        timeline.ingest(TimelineRead(7, listOf(ev(7, 6)), dropped = 2, lastSeq = 6))
        assertEquals(listOf("t2", "t3", "t6"), titles(timeline.page(null, 10, sinceMs = 10_002).events.filter { it.kind != Kinds.GAP }))
        val inputs = timeline.page(null, 10, sources = setOf(Sources.INPUT)).events
        assertEquals(listOf(Kinds.CLICK, Kinds.GAP), inputs.map { it.kind })
        assertEquals(listOf("t3"), titles(timeline.page(null, 10, kinds = setOf(Kinds.NETWORK_REQUEST)).events.filter { it.kind != Kinds.GAP }))
    }

    @Test
    fun `the end cursor skips everything kept`() {
        timeline.ingest(read(7, 1, 2))
        assertEquals(emptyList(), timeline.page(timeline.end(), 10).events)
    }

    @Test
    fun `a window that is not the app's comes in as a ui event`() {
        timeline.ingest(read(7, 1))
        val id = timeline.hostWindow(appeared = true, WindowRef("permission", WindowKind.SYSTEM, app = false), at = 10_500, durationMs = null, appearedId = null)
        val e = timeline.page(null, 10).events.last()
        assertEquals(id, e.id)
        assertEquals(Kinds.ui("window-appeared"), e.kind)
        assertEquals(Sources.UI, e.source)
        assertFalse(e.ui!!.window!!.app)
    }

    @Test
    fun `facet changes in events reach the facet table`() {
        val change = io.yamsergey.dta.protocol.timeline.FacetChange("app.cart", "items")
        timeline.ingest(TimelineRead(7, listOf(ev(7, 1).copy(facets = listOf(change))), lastSeq = 1))
        assertEquals("items", timeline.facets.current(emptyList()).facets.single().value)
    }

    @Test
    fun `the kept events of the followed process are available for causes and states`() {
        timeline.ingest(read(7, 1, 2))
        timeline.connectionLost()
        timeline.ingest(read(8, 1))
        assertEquals(listOf("8:1"), timeline.currentEpochEvents().map { it.id })
        assertEquals(listOf("7:1", "7:2"), timeline.kept().filter { it.id.startsWith("7:") }.map { it.id })
    }
}
