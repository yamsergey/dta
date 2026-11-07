package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.Flow
import io.yamsergey.dta.protocol.timeline.SequenceFlow
import io.yamsergey.dta.protocol.timeline.SequenceLane
import io.yamsergey.dta.protocol.timeline.SequencePage
import io.yamsergey.dta.protocol.timeline.SequenceStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SequenceViewTest {
    @Test
    fun `rows arriving do not read as the user scrolling away, only a moved bar does`() {
        val w = ScrollWatch()
        assertTrue(w.userMoved(100, adjusting = false, programmatic = false), "first look")
        assertEquals(false, w.userMoved(100, adjusting = false, programmatic = false), "the bar grew, its value stayed")
        assertEquals(false, w.userMoved(200, adjusting = false, programmatic = true), "our own scroll")
        assertEquals(false, w.userMoved(150, adjusting = true, programmatic = false), "still dragging")
        assertTrue(w.userMoved(150, adjusting = false, programmatic = false), "the drag ended there")
    }

    private fun flow(id: String, name: String, start: Long, end: Long?, rows: Int = 0, shown: Int = rows, gone: Boolean = false) =
        SequenceFlow(Flow(id, name, startMs = start, endMs = end), rows, shown, gone)
    private fun inFlow(ms: Long, vararg flows: String) = SequenceStep(ms, "user", "app", "tap", flows = flows.toList())
    private fun flowPage(steps: List<SequenceStep>, flows: List<SequenceFlow>, total: Int = steps.size, matched: Int = total) =
        SequencePage(steps, total, lanes, matched, 0, 0, 0, 1_000, flows = flows)
    private fun SequenceView.shape() = items.map { i -> when (i) { is SequenceItem.Line -> "line:" + i.flow.flow.id; is SequenceItem.Row -> "row:" + i.index + (i.flow?.let { "/" + it.flow.id } ?: "") } }

    @Test
    fun `a flow's line stands above its first row, nothing else moves`() {
        val v = SequenceView(page = flowPage(listOf(inFlow(1_000), inFlow(2_000, "f1"), inFlow(3_000, "f1"), inFlow(4_000)), listOf(flow("f1", "Buy", 1_500, 3_500, 2))))
        assertEquals(listOf("row:0", "line:f1", "row:1/f1", "row:2/f1", "row:3"), v.shape())
    }

    @Test
    fun `no flows, no lines`() {
        assertEquals(listOf("row:0"), SequenceView(page = flowPage(listOf(inFlow(1_000)), emptyList())).shape())
        assertEquals(emptyList(), SequenceView().items)
    }

    @Test
    fun `a flow with no rows stands by its time, a recording one at the end`() {
        val v = SequenceView(page = flowPage(listOf(inFlow(1_000), inFlow(4_000)), listOf(flow("f1", "Empty", 2_000, 2_000), flow("f2", "Now", 5_000, null))))
        assertEquals(listOf("row:0", "line:f1", "row:1", "line:f2"), v.shape())
    }

    @Test
    fun `a filter keeps a line while any of its rows stay`() {
        val kept = SequenceView(page = flowPage(listOf(inFlow(2_000, "f1")), listOf(flow("f1", "Buy", 1_500, 3_500, 7, 1), flow("f2", "Other", 4_000, 5_000, 3, 0)), total = 10, matched = 1))
        assertEquals(listOf("line:f1", "row:0/f1"), kept.shape())
    }

    @Test
    fun `after Clear a finished flow before it has no line, one reaching into the view stands at its first row`() {
        val v = SequenceView(
            startMs = 3_000,
            page = flowPage(listOf(inFlow(3_100, "f2"), inFlow(4_000)), listOf(flow("f1", "Old", 1_000, 2_000, 5, 0), flow("f2", "Reach", 2_500, 3_500, 4, 1))),
        )
        assertEquals(listOf("line:f2", "row:0/f2", "row:1"), v.shape())
        assertTrue("started before this view" in SequenceText.flowMeta(v.flows[1], 1_000, v.startMs), SequenceText.flowMeta(v.flows[1], 1_000, v.startMs))
    }

    @Test
    fun `the line says when, how many and where from, or that it is recording`() {
        assertEquals(" · 0:17.8 to 0:18.1 · 4 rows · manual", SequenceText.flowMeta(flow("f", "A", 18_800, 19_100, 4), 1_000))
        assertEquals(" · 0:17.8 to 0:18.1 · 1 row · manual", SequenceText.flowMeta(flow("f", "A", 18_800, 19_100, 1), 1_000))
        assertEquals(" · 0:17.8 to 0:18.1 · 2 of 7 rows · manual", SequenceText.flowMeta(flow("f", "A", 18_800, 19_100, 7, 2), 1_000))
        assertEquals(" · recording since 0:17.8 · manual", SequenceText.flowMeta(flow("f", "A", 18_800, null, 3), 1_000))
        assertEquals(" · recording since 0:17.8 · 1 of 3 rows · manual", SequenceText.flowMeta(flow("f", "A", 18_800, null, 3, 1), 1_000))
        assertTrue("older rows no longer kept" in SequenceText.flowMeta(flow("f", "A", 18_800, 19_100, 1, gone = true), 1_000))
        assertEquals("● Now", SequenceText.flowTitle(flow("f", "Now", 1, null)))
        assertEquals("Then", SequenceText.flowTitle(flow("f", "Then", 1, 2)))
    }

    @Test
    fun `a hover says a row is in its flow by time, not by a proved cause`() {
        val text = SequenceText.hover(inFlow(1, "f1"), mapOf("f1" to "Buy"))
        assertTrue("In Buy by time, not by a proved cause" in text, text)
        assertEquals("tap", SequenceText.hover(inFlow(1)))
    }

    @Test
    fun `the caption counts flows or names the recording one, and offers to end it`() {
        val two = SequenceView(page = flowPage(listOf(inFlow(1_000)), listOf(flow("a", "A", 1, 2), flow("b", "B", 2, 3))))
        assertEquals("1 row · 2 flows", two.caption)
        assertEquals(null, two.action)
        val rec = SequenceView(page = flowPage(listOf(inFlow(1_000)), listOf(flow("a", "A", 1, null))))
        assertEquals("1 row · recording A", rec.caption)
        assertEquals(SequenceAction.END_FLOW, rec.action)
        assertEquals("A", rec.recording?.flow?.name)
        assertEquals(SequenceAction.RESUME, rec.pause(true).action)
    }

    @Test
    fun `while paused the recording flow follows the page waiting`() {
        val v = SequenceView(page = flowPage(listOf(inFlow(1_000)), emptyList())).pause(true)
            .receive(flowPage(listOf(inFlow(1_000)), listOf(flow("a", "A", 2_000, null))), "")
        assertEquals("A", v.recording?.flow?.name)
        assertEquals(listOf("row:0"), v.shape(), "the view stands until resumed")
    }

    @Test
    fun `steps map to their place among the items, a current hit stays right`() {
        val v = SequenceView(page = flowPage(listOf(inFlow(2_000, "f1"), inFlow(3_000, "f1")), listOf(flow("f1", "Buy", 1_500, null, 2))))
        assertEquals(2, v.itemOfStep(1))
        assertEquals(0, v.itemOfFlow("f1"))
        assertEquals(-1, v.itemOfFlow("no"))
    }

    @Test
    fun `the list is newest first, and says when a flow cannot be gone to`() {
        val v = SequenceView(page = flowPage(listOf(inFlow(2_000, "b")), listOf(flow("a", "Login", 100, 200, 0, gone = true), flow("b", "Buy", 1_800, 2_500, 4), flow("c", "Hid", 3_000, 3_500, 2, 0)), total = 8, matched = 1))
        assertEquals(
            listOf(FlowMenuItem("c", "Hid", "hidden by the filter", false), FlowMenuItem("b", "Buy", "0:00.8 · 4 rows", true), FlowMenuItem("a", "Login", "rows no longer kept", false)),
            v.flowMenu(),
        )
    }

    @Test
    fun `going to a flow stops following and brings back rows Clear left out`() {
        val base = SequenceView(page = flowPage(listOf(inFlow(5_000)), listOf(flow("a", "A", 1_000, 2_000, 3, 0))), startMs = 4_000, following = true)
        val v = base.goToFlow("a")
        assertEquals("a", v.flowTarget)
        assertEquals(false, v.following)
        assertEquals(null, v.startMs)
        assertEquals(base, base.goToFlow("missing"))
        val after = SequenceView(page = flowPage(listOf(inFlow(5_000)), listOf(flow("a", "A", 4_500, 5_500, 1))), startMs = 4_000).goToFlow("a")
        assertEquals(4_000L, after.startMs)
    }

    @Test
    fun `lines list flows, name them on rows, and asked for one say so`() {
        val p = flowPage(listOf(inFlow(1_000), inFlow(2_000, "f1")), listOf(flow("f1", "Buy", 1_500, 3_000, 1)))
        val lines = SequenceText.lines(p)
        assertEquals("2 rows", lines[0])
        assertEquals("flow f1 Buy · 0:00.5 to 0:02.0 · 1 row · manual", lines[1])
        assertTrue(lines[3].endsWith("(in Buy)"), lines[3])
        assertTrue("(in" !in lines[2], lines[2])
        val one = SequenceText.lines(p.copy(steps = p.steps.drop(1), total = 1, matched = 1, onlyFlow = "f1"))
        assertEquals("1 row", one[0])
        assertEquals("flow f1 Buy · 0:00.5 to 0:02.0 · 1 row · manual", one[1])
        assertTrue("(in" !in one[2], one[2])
    }

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
