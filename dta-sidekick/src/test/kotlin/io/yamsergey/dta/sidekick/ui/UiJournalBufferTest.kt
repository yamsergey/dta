package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 015 (T006): the sidekick's ring of UI events, read after a seq, overflow counted. */
class UiJournalBufferTest {
    private val pushed = mutableListOf<UiEvent>()
    private val buffer = UiJournalBuffer(capacity = 3, epoch = 100, publish = { pushed += it })

    private fun toast(text: String) = buffer.append { id, seq -> UiEvent(id, seq, 1, 2, UiEventKind.TOAST, UiSource.TOAST, text = text) }

    @Test
    fun `events get ids and seqs in order and are pushed`() {
        val a = toast("a")
        val b = toast("b")
        assertEquals("100:1", a.id)
        assertEquals(2, b.seq)
        assertEquals(listOf(a, b), pushed)
    }

    @Test
    fun `read after a seq returns the later events`() {
        toast("a"); toast("b"); toast("c")
        val read = buffer.read(afterSeq = 1, limit = 10)
        assertEquals(listOf("b", "c"), read.events.map { it.text })
        assertEquals(0, read.dropped)
        assertEquals(3, read.lastSeq)
        assertEquals(100, read.epoch)
    }

    @Test
    fun `overflow is counted for a reader behind it`() {
        repeat(5) { toast("t$it") }
        val behind = buffer.read(afterSeq = 1, limit = 10)
        assertEquals(listOf("t2", "t3", "t4"), behind.events.map { it.text })
        assertEquals(1, behind.dropped)
        assertEquals(2, buffer.read(afterSeq = null, limit = 10).dropped)
    }

    @Test
    fun `limit pages from the oldest`() {
        toast("a"); toast("b"); toast("c")
        assertEquals(listOf("a", "b"), buffer.read(afterSeq = null, limit = 2).events.map { it.text })
    }
}
