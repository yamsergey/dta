package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.SequenceLane
import io.yamsergey.dta.protocol.timeline.SequencePage
import io.yamsergey.dta.protocol.timeline.SequenceStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SequenceViewTest {
    private val lanes = listOf(SequenceLane("user", "You"), SequenceLane("app", "App"), SequenceLane("host:a", "a"), SequenceLane("socket", "socket"))
    private fun step(ms: Long, label: String = "tap", hit: Boolean = false) = SequenceStep(ms, "user", "app", label, hit = hit)
    private fun page(vararg s: SequenceStep, total: Int = s.size, matched: Int = total, beforeStart: Int = 0, hits: Int = s.count { it.hit }) =
        SequencePage(s.toList(), total, lanes, matched, beforeStart, hits, 0, 1_000)

    @Test
    fun `a linked row is marked and its arrow leaves out the navigation's own`() {
        assertEquals("⤷ Home", SequenceText.label(SequenceStep(1, "app", label = "→ Home", linked = true)))
        assertEquals("→ Home", SequenceText.label(SequenceStep(1, "app", label = "→ Home")))
    }

    @Test
    fun `the hover adds what the label does not say`() {
        assertEquals("GET /a\nh:1/a", SequenceText.hover(SequenceStep(1, "app", "host:h", "GET /a", detail = "h:1/a")))
        assertEquals("tap", SequenceText.hover(SequenceStep(1, "user", "app", "tap", detail = "tap")))
    }

    @Test
    fun `lines say what is here, then each row with its lanes and marks`() {
        val lines = SequenceText.lines(page(step(1_000, "tap \"Go\""), SequenceStep(7_700, "app", label = "→ Home", linked = true), SequenceStep(8_000, "host:a", "app", "503", back = true, failed = true)))
        assertEquals("3 rows", lines[0])
        assertEquals("0.00  You → App  tap \"Go\"", lines[1])
        assertEquals("6.70  App  ⤷ Home", lines[2])
        assertEquals("7.00  a → App  503  (failed)", lines[3])
    }

    @Test
    fun `counts state what the filter, the limit and the start left out`() {
        assertEquals("2 of 9 rows match · the newest 1 here · 3 before the start left out", SequenceText.counts(page(step(1), total = 9, matched = 2, beforeStart = 3)))
        assertTrue("Nothing recorded" in SequenceText.lines(page())[0])
    }

    @Test
    fun `lanes keep their places`() {
        val p = SequenceView(page()).positions()
        assertEquals(0.06, p["user"])
        assertEquals(0.32, p["app"])
        assertEquals(0.70, p["host:a"])
        assertEquals(0.94, p["socket"])
    }

    @Test
    fun `pause holds the page and counts what waits, resume catches up, a new query is shown at once`() {
        val one = page(step(1))
        val two = page(step(1), step(2), step(3))
        val paused = SequenceView().receive(one, "").pause(true).receive(two, "")
        assertEquals(1, paused.steps.size)
        assertEquals(2, paused.waiting)
        assertEquals("Paused · 1 row · 2 new waiting", paused.caption)
        assertEquals(SequenceAction.RESUME, paused.action)
        assertEquals(3, paused.pause(false).steps.size)
        assertTrue(paused.pause(false).following)
        assertEquals(3, paused.withQuery("x").receive(two, "x").steps.size)
    }

    @Test
    fun `clear starts after the newest row, show all goes back`() {
        val v = SequenceView().receive(page(step(1_000), step(2_000)), "")
        val cleared = v.clear()
        assertEquals(2_001, cleared.startMs)
        assertEquals(SequenceAction.SHOW_ALL, cleared.action)
        val read = cleared.receive(page(total = 0, beforeStart = 2), "")
        assertEquals("0 rows · from 0:01.0, 2 before it are not shown", read.caption)
        assertEquals(null, read.showAll().startMs)
    }

    @Test
    fun `clear with nothing seen does nothing`() {
        assertEquals(null, SequenceView().clear().startMs)
    }

    @Test
    fun `the filter's work is in the caption`() {
        assertEquals("2 of 9 rows shown · 7 hidden by the filter", SequenceView().receive(page(step(1), step(2), total = 9, matched = 2), "status:5xx").caption)
        assertEquals("9 rows · the newest 2 are drawn", SequenceView().receive(page(step(1), step(2), total = 9), "").caption)
    }

    @Test
    fun `hits step around and stop following, the label says where`() {
        val v = SequenceView().receive(page(step(1, hit = true), step(2), step(3, hit = true)), "w")
        assertEquals("– / 2", v.hitLabel(true))
        assertEquals(null, v.hitLabel(false))
        val first = v.nextHit()
        assertEquals(0, first.currentRow)
        assertEquals("1 / 2", first.hitLabel(true))
        assertEquals(false, first.following)
        assertEquals(2, first.nextHit().currentRow)
        assertEquals(0, first.nextHit().nextHit().currentRow)
        assertEquals(2, v.previousHit().currentRow)
        assertEquals(-1, SequenceView().receive(page(step(1)), "w").nextHit().currentRow)
    }

    @Test
    fun `lane titles' rooms never overlap at any width`() {
        val ids = listOf("user", "app", "host:a", "host:b", "socket")
        val at = mapOf("user" to 0.06, "app" to 0.32, "host:a" to 0.64, "host:b" to 0.79, "socket" to 0.94)
        for (width in listOf(100, 240, 480, 624, 900, 2400)) {
            val rooms = LaneTitles.of(ids, width) { (62 + (width - 62) * at[it]!!).toInt() }
            assertEquals(0, rooms.first().from)
            assertEquals(width, rooms.last().to)
            rooms.zipWithNext().forEach { (a, b) -> assertTrue(a.to <= b.from, "$width: $a $b") }
        }
    }
}
