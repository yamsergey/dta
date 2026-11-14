package io.yamsergey.dta.sidekick.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import io.yamsergey.dta.protocol.timeline.ScrollPosition
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ShimReflect
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer
import io.yamsergey.dta.sidekick.util.NoArgMethods
import java.util.IdentityHashMap

/**
 * Feature 064 (US3, research R3/R7): Compose scroll gestures, one event each. ENTRY hooks on the
 * scrollable's drag start and stop run once per gesture (never per pointer event or frame); after
 * the stop a check every 100 ms on the main thread asks the scrollable's state whether it still
 * moves (1–3 checks for a fling). The container's chain is read once, after it settles, in its
 * own main-thread message. Foundation 1.12 moved the drag start to AbstractScrollableNode and
 * renamed the scrolling logic; both shapes are looked up, a missing one is stated. Never throws.
 */
class ScrollRecorder(
    private val buffer: TimelineBuffer,
    private val windowIdOf: (View) -> String? = { null },
) {
    /** Null when installed; otherwise why scrolls are not recorded. */
    var failure: String? = null
        private set

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val wallStart = IdentityHashMap<Any, Long>()
    private val tracker = ScrollTracker { g -> main.post { record(g) } }

    fun install(loader: ClassLoader?) {
        val node = runCatching { Class.forName(SCROLLABLE, false, loader) }.getOrNull()
            ?: return run { failure = "this Compose foundation version has no ScrollableNode; scrolls are not recorded" }
        val starts = listOf(node, runCatching { Class.forName(ABSTRACT, false, loader) }.getOrNull()).filterNotNull()
            .firstOrNull { c -> ShimReflect.descriptors(c, START).any { it == START_DESCRIPTOR } }
        val stops = ShimReflect.descriptors(node, STOP).any { it == STOP_DESCRIPTOR }
        if (starts == null || !stops) {
            failure = "this Compose foundation version has no scroll drag start or stop for DTA to observe; scrolls are not recorded"
            return
        }
        val results = listOf(
            Hooks.entry(starts, START, START_DESCRIPTOR, EntryHandler { self, _ -> started(self) }),
            Hooks.entry(node, STOP, STOP_DESCRIPTOR, EntryHandler { self, _ -> stopped(self) }),
        )
        failure = results.filterNot { it is Hooks.Install.Installed }.map { it.describe() }.distinct().joinToString("; ").ifEmpty { null }
    }

    private fun started(node: Any?) {
        try {
            if (node == null) return
            val state = stateOf(node) ?: return
            wallStart[node] = System.currentTimeMillis()
            tracker.started(node, SystemClock.uptimeMillis(), position(state), vertical(node))
        } catch (_: Throwable) {
        }
    }

    private fun stopped(node: Any?) {
        try {
            if (node == null) return
            tracker.stopped(node, SystemClock.uptimeMillis())
            main.postDelayed({ check(node) }, ScrollTracker.CHECK_MS)
        } catch (_: Throwable) {
        }
    }

    private fun check(node: Any) {
        try {
            val state = stateOf(node)
            val moving = state?.let { NoArgMethods.call(it, "isScrollInProgress") as? Boolean } ?: false
            if (tracker.check(node, SystemClock.uptimeMillis(), moving, state?.let(::position))) main.postDelayed({ check(node) }, ScrollTracker.CHECK_MS)
        } catch (_: Throwable) {
        }
    }

    private fun record(g: ScrollTracker.Gesture) {
        try {
            val wall = wallStart.remove(g.node) ?: System.currentTimeMillis()
            val found = ComposeTargets.of(g.node, windowIdOf, "Scrollable", null) ?: return
            val chain = TapChainReader.read(found.owner, found.layoutNode)
            buffer.append { id, seq -> InputEvents.scroll(id, seq, wall, g, found.target, chain) }
        } catch (_: Throwable) {
        }
    }

    private fun logic(node: Any): Any? =
        runCatching { ShimReflect.field(node, "scrollingLogic") }.getOrNull() ?: runCatching { ShimReflect.field(node, "scrollLogic") }.getOrNull()

    private fun stateOf(node: Any): Any? = logic(node)?.let { NoArgMethods.call(it, "getScrollableState") }

    private fun vertical(node: Any): Boolean = logic(node)?.let { NoArgMethods.call(it, "isVertical") as? Boolean } ?: true

    /** A lazy list's or grid's first visible item and offset, or a plain scroll's value. */
    private fun position(state: Any): ScrollPosition? {
        val item = NoArgMethods.call(state, "getFirstVisibleItemIndex") as? Int
        val offset = NoArgMethods.call(state, "getFirstVisibleItemScrollOffset") as? Int
        if (item != null && offset != null) return ScrollPosition(item, offset)
        return (NoArgMethods.call(state, "getValue") as? Int)?.let { ScrollPosition(null, it) }
    }

    private companion object {
        const val SCROLLABLE = "androidx.compose.foundation.gestures.ScrollableNode"
        const val ABSTRACT = "androidx.compose.foundation.gestures.AbstractScrollableNode"
        const val START = "onDragStarted-k-4lQ0M"
        const val START_DESCRIPTOR = "(J)V"
        const val STOP = "onDragStopped"
        const val STOP_DESCRIPTOR = "(Landroidx/compose/foundation/gestures/DragEvent\$DragStopped;)V"
    }
}
