package io.yamsergey.dta.sidekick.input

import io.yamsergey.dta.protocol.timeline.ScrollDirection
import io.yamsergey.dta.protocol.timeline.ScrollPosition
import java.util.IdentityHashMap

/**
 * Feature 064 (US3, research R3): one scroll gesture as one event. A drag's start and stop come
 * from the scrollable's own drag callbacks (once per gesture); after the stop the owner checks
 * every [CHECK_MS] whether it still moves, and the gesture ends when it settles, when a new touch
 * starts on the same scrollable (a touch that stops a fling), or after [MAX_CHECKS] checks. A
 * gesture that did not move the content is not a scroll. Pure: positions and times are given.
 */
class ScrollTracker(private val emit: (Gesture) -> Unit) {

    data class Gesture(
        val node: Any,
        val startMono: Long,
        val endMono: Long,
        val from: ScrollPosition?,
        val to: ScrollPosition?,
        val direction: ScrollDirection?,
        val fling: Boolean,
        val stoppedByTouch: Boolean,
    )

    private class Open(val startMono: Long, val from: ScrollPosition?, val vertical: Boolean) {
        var settling = false
        var checks = 0
        /** A check saw it still moving after the finger let go: a fling. */
        var moving = false
        var last: ScrollPosition? = from
    }

    private val open = IdentityHashMap<Any, Open>()

    /** A drag started on [node]; one still settling there ends as stopped by this touch. */
    fun started(node: Any, mono: Long, position: ScrollPosition?, vertical: Boolean) {
        open[node]?.takeIf { it.settling }?.let { end(node, it, mono, position, stoppedByTouch = true) }
        open[node] = Open(mono, position, vertical)
    }

    /** The finger let go; checks follow. */
    fun stopped(node: Any, @Suppress("UNUSED_PARAMETER") mono: Long) {
        open[node]?.settling = true
    }

    /** One settle check; true when another should follow. */
    fun check(node: Any, mono: Long, inProgress: Boolean, position: ScrollPosition?): Boolean {
        val g = open[node]?.takeIf { it.settling } ?: return false
        g.checks++
        if (position != null) g.last = position
        if (inProgress) g.moving = true
        if (inProgress && g.checks < MAX_CHECKS) return true
        end(node, g, mono, position ?: g.last, stoppedByTouch = false)
        return false
    }

    private fun end(node: Any, g: Open, mono: Long, to: ScrollPosition?, stoppedByTouch: Boolean) {
        open.remove(node)
        val direction = direction(g.from, to, g.vertical)
        if (direction == null && g.from != null && to != null) return
        emit(Gesture(node, g.startMono, mono, g.from, to, direction, g.moving, stoppedByTouch))
    }

    companion object {
        const val CHECK_MS = 100L
        /** 3 s: a fling longer than that is ended where it is. */
        const val MAX_CHECKS = 30

        fun direction(from: ScrollPosition?, to: ScrollPosition?, vertical: Boolean): ScrollDirection? {
            if (from == null || to == null) return null
            val c = compareValuesBy(from, to, { it.item ?: 0 }, { it.offset })
            return when {
                c == 0 -> null
                c < 0 -> if (vertical) ScrollDirection.DOWN else ScrollDirection.RIGHT
                else -> if (vertical) ScrollDirection.UP else ScrollDirection.LEFT
            }
        }
    }
}
