package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 015 (T019, research R7): elements that came and went inside a window, without the noise. */
class ElementTrackerTest {
    private val events = mutableListOf<UiEvent>()
    private val buffer = UiJournalBuffer(epoch = 1) { events += it }
    private var wall = 0L
    private val tracker = ElementTracker(buffer, wallClock = { wall })
    private val window = WindowRef("w1", WindowKind.ACTIVITY, title = "MainActivity")

    private fun text(t: String, name: String? = null) = RawNode("TextView", text = t, name = name)
    private fun screen(vararg children: RawNode, scroll: List<RawNode>? = null, offset: String = "0") = RawNode(
        "FrameLayout",
        children = children.toList() + listOfNotNull(scroll?.let { RawNode("RecyclerView", name = "list", scroll = offset, children = it) }),
    )
    private fun sum(root: RawNode) = ContentSummaries.summarize(root, secure = false, settled = true, takenMonoMs = 0)

    private fun settle(before: RawNode, after: RawNode, mono: Long, byCap: Boolean = false, costMs: Long = 1) =
        tracker.settled(window, sum(before), sum(after), byCap, costMs, mono)

    @Test
    fun `an element that comes and goes is recorded with its lifetime`() {
        val base = screen(text("Title", "title"))
        val withSnack = screen(text("Title", "title"), RawNode("LinearLayout", name = "snackbar", children = listOf(text("Undo?", "snackbar_text"))))
        settle(base, withSnack, mono = 1_000)
        settle(withSnack, base, mono = 3_000)
        assertEquals(listOf(UiEventKind.ELEMENT_APPEARED, UiEventKind.ELEMENT_DISAPPEARED), events.map { it.kind })
        assertEquals("Undo?", events[0].text)
        assertEquals("snackbar", events[0].element?.parent)
        assertEquals(2_000, events[1].durationMs)
        assertEquals(events[0].id, events[1].appearedId)
    }

    @Test
    fun `a text that only changes is not an event`() {
        settle(screen(text("12:00", "clock")), screen(text("12:01", "clock")), mono = 1)
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `rows of a scrolled list are not events`() {
        settle(screen(scroll = listOf(text("row 1"), text("row 2")), offset = "0"), screen(scroll = listOf(text("row 2"), text("row 3"), text("row 4")), offset = "120"), mono = 1)
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `an element added to a list that did not scroll is an event`() {
        settle(screen(scroll = listOf(text("row 1")), offset = "0"), screen(scroll = listOf(text("row 1"), text("Error: offline")), offset = "0"), mono = 1)
        assertEquals(listOf("Error: offline"), events.map { it.text })
    }

    @Test
    fun `a whole new screen is not a burst of elements`() {
        val before = screen(*(1..8).map { text("a$it", "a$it") }.toTypedArray())
        val after = screen(*(1..8).map { text("b$it", "b$it") }.toTypedArray())
        settle(before, after, mono = 1)
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `progress indicators of a window that keeps drawing are not events`() {
        settle(screen(text("Title", "title")), screen(text("Title", "title"), RawNode("ProgressBar", progress = true)), mono = 1, byCap = true)
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `a busy window backs off and says for how long`() {
        val base = screen(text("Title", "title"))
        val more = screen(text("Title", "title"), text("Undo?", "snack"))
        wall = 10_000
        settle(base, more, mono = 1, costMs = 9)
        settle(more, base, mono = 2)
        assertEquals(listOf(UiEventKind.ELEMENT_APPEARED, UiEventKind.GAP), events.map { it.kind })
        assertEquals(10_000, events[1].gapFromMs)
        assertEquals(15_000, events[1].gapToMs)
        assertTrue(events[1].completeness.single().reason.startsWith("ui-backed-off"))
        wall = 16_000
        settle(base, more, mono = 3)
        assertEquals(UiEventKind.ELEMENT_APPEARED, events.last().kind)
    }

    @Test
    fun `a truncated summary does not invent comings and goings at its bound`() {
        val big = screen(*(1..60).map { text("r$it", "r$it") }.toTypedArray())
        val bigger = screen(text("Banner", "banner"), *(1..60).map { text("r$it", "r$it") }.toTypedArray())
        settle(big, bigger, mono = 1)
        assertEquals(listOf("Banner"), events.filter { it.kind == UiEventKind.ELEMENT_APPEARED }.map { it.text })
        assertTrue(events.none { it.kind == UiEventKind.ELEMENT_DISAPPEARED }, "$events")
    }
}
