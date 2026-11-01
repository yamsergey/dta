package io.yamsergey.dta.daemon.ui

import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiJournalRead
import io.yamsergey.dta.protocol.ui.UiSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 015 (T014, research R8): the host's journal of one app, read with cursors. */
class UiJournalTest {
    private var now = 1_000L
    private val journal = UiJournal(session = "s1", bound = 5, clock = { now })

    private fun ev(epoch: Long, seq: Long, text: String = "t$seq") =
        UiEvent("$epoch:$seq", seq, monoMs = seq, wallMs = 10_000 + seq, kind = UiEventKind.TOAST, source = UiSource.TOAST, text = text)

    private fun read(epoch: Long, vararg seqs: Long, dropped: Int = 0) =
        UiJournalRead(epoch, seqs.map { ev(epoch, it) }, dropped, lastSeq = seqs.maxOrNull() ?: 0)

    @Test
    fun `catch-up then live pushes, read in order with a continuing cursor`() {
        journal.ingest(read(7, 1, 2))
        assertTrue(journal.pushed(ev(7, 3)))
        val first = journal.page(since = null, limit = 2)
        assertEquals(listOf("t1", "t2"), first.events.map { it.text })
        assertTrue(first.more)
        val second = journal.page(since = first.next, limit = 10)
        assertEquals(listOf("t3"), second.events.map { it.text })
        assertFalse(second.more)
        assertEquals(emptyList(), journal.page(since = second.next, limit = 10).events)
    }

    @Test
    fun `a push out of order asks for a catch-up and is not stored`() {
        journal.ingest(read(7, 1))
        assertFalse(journal.pushed(ev(7, 3)))
        journal.ingest(read(7, 2, 3))
        assertEquals(listOf("t1", "t2", "t3"), journal.page(null, 10).events.map { it.text })
    }

    @Test
    fun `events already stored are not duplicated`() {
        journal.ingest(read(7, 1, 2))
        journal.ingest(read(7, 2, 3))
        assertFalse(journal.pushed(ev(7, 2)))
        assertEquals(listOf("t1", "t2", "t3"), journal.page(null, 10).events.map { it.text })
    }

    @Test
    fun `events the app's ring lost become a gap`() {
        journal.ingest(read(7, 1))
        journal.ingest(read(7, 5, dropped = 3))
        val events = journal.page(null, 10).events
        assertEquals(listOf(UiEventKind.TOAST, UiEventKind.GAP, UiEventKind.TOAST), events.map { it.kind })
        assertEquals(3, events[1].gapCount)
        assertTrue(events[1].completeness.single().reason.startsWith("ui-events-dropped"))
    }

    @Test
    fun `a new process appends the end of the old one at the moment its link was lost`() {
        journal.ingest(read(7, 1))
        now = 2_000
        journal.connectionLost()
        now = 9_000
        journal.ingest(read(8, 1))
        val events = journal.page(null, 10).events
        assertEquals(listOf(UiEventKind.TOAST, UiEventKind.PROCESS_ENDED, UiEventKind.TOAST), events.map { it.kind })
        assertEquals(2_000, events[1].wallMs)
    }

    @Test
    fun `a process that ended is recorded once`() {
        journal.ingest(read(7, 1))
        journal.connectionLost()
        journal.processEnded()
        journal.processEnded()
        journal.ingest(read(8, 1))
        assertEquals(1, journal.page(null, 10).events.count { it.kind == UiEventKind.PROCESS_ENDED })
    }

    @Test
    fun `beyond the bound the oldest go, and a reader behind them is told`() {
        val behind = journal.page(null, 1).next
        journal.ingest(read(7, 1, 2, 3, 4, 5, 6, 7))
        val page = journal.page(behind, 10)
        assertEquals(UiEventKind.GAP, page.events.first().kind)
        assertTrue(page.events.first().completeness.single().reason.startsWith("ui-history-evicted"))
        assertEquals(listOf("t3", "t4", "t5", "t6", "t7"), page.events.drop(1).map { it.text })
    }

    @Test
    fun `a cursor of another journal reads from the oldest, saying so`() {
        journal.ingest(read(7, 1))
        val page = journal.page("other:3", 10)
        assertEquals(UiEventKind.GAP, page.events.first().kind)
        assertEquals("t1", page.events[1].text)
    }

    @Test
    fun `filters by kind and time`() {
        journal.ingest(read(7, 1, 2, 3))
        assertEquals(listOf("t2", "t3"), journal.page(null, 10, sinceMs = 10_002).events.map { it.text })
        assertEquals(emptyList(), journal.page(null, 10, kinds = setOf("window")).events)
        assertEquals(3, journal.page(null, 10, kinds = setOf("toast")).events.size)
    }

    @Test
    fun `the end cursor skips everything kept`() {
        journal.ingest(read(7, 1, 2))
        assertEquals(emptyList(), journal.page(journal.end(), 10).events)
    }
}
