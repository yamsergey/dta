package io.yamsergey.dta.sidekick.input

import io.yamsergey.dta.protocol.timeline.ScrollDirection
import io.yamsergey.dta.protocol.timeline.ScrollPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 064 C2: one scroll gesture becomes one event, from the drag's start until it settles (research R3). */
class ScrollTrackerTest {
    private val out = ArrayList<ScrollTracker.Gesture>()
    private val tracker = ScrollTracker { out += it }
    private val list = Any()
    private fun p(item: Int, offset: Int) = ScrollPosition(item, offset)

    @Test
    fun `a slow drag settles at the first check and is not a fling`() {
        tracker.started(list, 100, p(0, 0), vertical = true)
        tracker.stopped(list, 600)
        assertFalse(tracker.check(list, 700, inProgress = false, position = p(2, 40)))
        val g = out.single()
        assertEquals(100, g.startMono)
        assertEquals(700, g.endMono)
        assertEquals(p(0, 0) to p(2, 40), g.from to g.to)
        assertEquals(ScrollDirection.DOWN, g.direction)
        assertFalse(g.fling)
        assertFalse(g.stoppedByTouch)
    }

    @Test
    fun `a fling keeps being checked until it settles, then is one event`() {
        tracker.started(list, 100, p(0, 361), vertical = true)
        tracker.stopped(list, 300)
        assertTrue(tracker.check(list, 400, inProgress = true, position = p(4, 0)))
        assertTrue(tracker.check(list, 500, inProgress = true, position = p(7, 0)))
        assertTrue(out.isEmpty(), "nothing before it settles")
        assertFalse(tracker.check(list, 600, inProgress = false, position = p(8, 393)))
        val g = out.single()
        assertTrue(g.fling)
        assertEquals(600, g.endMono)
        assertEquals(p(8, 393), g.to)
    }

    @Test
    fun `a touch during the fling ends it as stopped by a touch, and its own still drag is no scroll`() {
        tracker.started(list, 100, p(0, 0), vertical = true)
        tracker.stopped(list, 300)
        assertTrue(tracker.check(list, 400, inProgress = true, position = p(5, 0)))
        tracker.started(list, 450, p(6, 10), vertical = true)
        val g = out.single()
        assertTrue(g.stoppedByTouch)
        assertEquals(450, g.endMono)
        assertEquals(p(6, 10), g.to)
        tracker.stopped(list, 451)
        assertFalse(tracker.check(list, 551, inProgress = false, position = p(6, 10)))
        assertEquals(1, out.size, "a drag that did not move the list is not a scroll")
    }

    @Test
    fun `up, left, right and no movement`() {
        fun one(from: ScrollPosition, to: ScrollPosition, vertical: Boolean): ScrollDirection? {
            out.clear()
            tracker.started(list, 0, from, vertical)
            tracker.stopped(list, 10)
            tracker.check(list, 110, false, to)
            return out.singleOrNull()?.direction
        }
        assertEquals(ScrollDirection.UP, one(p(3, 0), p(1, 20), vertical = true))
        assertEquals(ScrollDirection.RIGHT, one(p(0, 0), p(0, 80), vertical = false))
        assertEquals(ScrollDirection.LEFT, one(ScrollPosition(null, 300), ScrollPosition(null, 100), vertical = false))
        assertEquals(null, one(p(2, 5), p(2, 5), vertical = true), "not emitted at all")
    }

    @Test
    fun `a check for a node not settling asks for no more checks, and a fling is not checked forever`() {
        assertFalse(tracker.check(list, 0, inProgress = true, position = null))
        tracker.started(list, 0, p(0, 0), vertical = true)
        tracker.stopped(list, 10)
        var t = 110L
        var checks = 0
        while (tracker.check(list, t, inProgress = true, position = p(1, 0))) { t += 100; checks++ }
        assertEquals(ScrollTracker.MAX_CHECKS - 1, checks)
        assertTrue(out.single().fling)
    }
}
