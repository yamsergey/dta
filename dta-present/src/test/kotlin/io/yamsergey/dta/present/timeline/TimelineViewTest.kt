package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.flow
import io.yamsergey.dta.present.timeline.TimelineFixture.request
import io.yamsergey.dta.present.timeline.TimelineFixture.toast
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.ui.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 017 (T001): the Timeline tab's state, reduced from pages, live events and the user's actions. */
class TimelineViewTest {
    private val empty = TimelineView()

    @Test
    fun `pages and live events merge by id, ordered by time`() {
        val v = empty.reduce(TimelineChange.Arrived(toast))
            .reduce(TimelineChange.Loaded(flow))
            .reduce(TimelineChange.Arrived(request))
        assertEquals(flow.map { it.id }, v.events.map { it.id })
    }

    @Test
    fun `sources and the current state are kept`() {
        val off = SourceStatus("nav", SourceState.OFF, "not used by the app")
        val v = empty.reduce(TimelineChange.Sources(listOf(off)))
            .reduce(TimelineChange.State(listOf(FacetValue("app.cart", "items", "app", 1, 1))))
        assertEquals(listOf(off), v.sources)
        assertEquals("items", v.current.single().value)
    }

    @Test
    fun `follow is on and keeps the newest in view, a pan turns it off, follow brings it back`() {
        val v = empty.reduce(TimelineChange.Loaded(flow))
        assertTrue(v.window.follow)
        assertTrue(v.window.endMs >= flow.last().wallMs)
        val panned = v.reduce(TimelineChange.Pan(-500))
        assertFalse(panned.window.follow)
        assertEquals(v.window.endMs - 500, panned.window.endMs)
        val later = panned.reduce(TimelineChange.Arrived(toast.copy(id = "7:99", seq = 99, wallMs = 9_000)))
        assertEquals(panned.window.endMs, later.window.endMs, "not following: the view stays")
        assertTrue(later.reduce(TimelineChange.Follow(true)).window.endMs >= 9_000)
    }

    @Test
    fun `zoom is clamped and keeps its anchor in place`() {
        val v = empty.reduce(TimelineChange.Loaded(flow)).reduce(TimelineChange.Follow(false))
        val anchor = 1_500L
        val px = (v.window.endMs - anchor) / v.window.msPerPx
        val z = v.reduce(TimelineChange.Zoom(0.5, anchor))
        assertEquals(v.window.msPerPx * 0.5, z.window.msPerPx)
        assertEquals(px, (z.window.endMs - anchor) / z.window.msPerPx, 1.0)
        assertEquals(ViewWindow.MIN_MS_PER_PX, v.reduce(TimelineChange.Zoom(1e-9, anchor)).window.msPerPx)
        assertEquals(ViewWindow.MAX_MS_PER_PX, v.reduce(TimelineChange.Zoom(1e9, anchor)).window.msPerPx)
    }

    @Test
    fun `the selection is kept by id, and said to be gone when its event is dropped`() {
        val v = TimelineView(bound = 3).reduce(TimelineChange.Loaded(flow.take(3))).reduce(TimelineChange.Select(click.id))
        assertEquals(click.id, v.selectedId)
        assertFalse(v.selectedGone)
        val dropped = v.reduce(TimelineChange.Arrived(toast))
        assertEquals(click.id, dropped.selectedId)
        assertTrue(dropped.selectedGone)
        assertEquals(3, dropped.events.size)
    }

    @Test
    fun `filters and reset`() {
        val v = empty.reduce(TimelineChange.Loaded(flow)).reduce(TimelineChange.Filter(TimelineFilter(sources = setOf("network"))))
        assertEquals(2, v.shown.size)
        val reset = v.reduce(TimelineChange.Reset)
        assertEquals(TimelineView(), reset)
    }
}
