package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Features 015/016 (T006): the sidekick's ring of timeline events, read after a seq, overflow counted. */
class TimelineBufferTest {
    private val pushed = mutableListOf<TimelineEvent>()
    private val buffer = TimelineBuffer(capacity = 3, epoch = 100, publish = { pushed += it })

    private fun click(title: String) = buffer.append { id, seq -> TimelineEvent(id, seq, 1, 2, Sources.INPUT, Kinds.CLICK, title) }

    @Test
    fun `events get ids and seqs in order and are pushed`() {
        val a = click("a")
        val b = click("b")
        assertEquals("100:1", a.id)
        assertEquals(2, b.seq)
        assertEquals(listOf(a, b), pushed)
    }

    @Test
    fun `read after a seq returns the later events`() {
        click("a"); click("b"); click("c")
        val read = buffer.read(afterSeq = 1, limit = 10)
        assertEquals(listOf("b", "c"), read.events.map { it.title })
        assertEquals(0, read.dropped)
        assertEquals(3, read.lastSeq)
        assertEquals(100, read.epoch)
    }

    @Test
    fun `overflow is counted for a reader behind it`() {
        repeat(5) { click("t$it") }
        val behind = buffer.read(afterSeq = 1, limit = 10)
        assertEquals(listOf("t2", "t3", "t4"), behind.events.map { it.title })
        assertEquals(1, behind.dropped)
        assertEquals(2, buffer.read(afterSeq = null, limit = 10).dropped)
    }

    @Test
    fun `limit pages from the oldest`() {
        click("a"); click("b"); click("c")
        assertEquals(listOf("a", "b"), buffer.read(afterSeq = null, limit = 2).events.map { it.title })
    }

    @Test
    fun `default capacity is a thousand`() {
        val big = TimelineBuffer(epoch = 1) {}
        repeat(1_001) { big.append { id, seq -> TimelineEvent(id, seq, 1, 2, Sources.INPUT, Kinds.CLICK) } }
        assertEquals(1, big.read(afterSeq = null, limit = 1).dropped)
        assertEquals(TimelineBuffer.CAPACITY, 1_000)
    }
}
