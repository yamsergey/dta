package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.ElementRef
import io.yamsergey.dta.protocol.ui.SummaryElement
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowRef

/**
 * Elements that come and go inside a window (feature 015 US4, research R7), from two settled
 * summaries of it. Not events: a text that only changed, rows of a list that scrolled, a whole
 * new screen (more than [burst] changes at once), progress of a window that keeps drawing, and
 * comings and goings at the summary's bound. A window that costs too much backs off for
 * [backOffMs], stated as a gap. Pure: the recorder hands it summaries on the main thread.
 */
class ElementTracker(
    private val buffer: UiJournalBuffer,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val burst: Int = BURST,
    private val budgetMs: Long = BUDGET_MS,
    private val backOffMs: Long = BACK_OFF_MS,
) {
    private class Shown(val id: String, val mono: Long)

    private val shown = HashMap<String, HashMap<String, Shown>>()
    private val caps = HashMap<String, Int>()
    private var backOffUntil = 0L

    fun settled(window: WindowRef, before: Summarized, after: Summarized, byCap: Boolean, costMs: Long, nowMono: Long) {
        val wall = wallClock()
        if (wall < backOffUntil) return
        val capsInARow = if (byCap) (caps[window.windowId] ?: 0) + 1 else 0
        caps[window.windowId] = capsInARow
        record(window, before, after, byCap, nowMono)
        if (costMs > budgetMs || capsInARow > MAX_CAPS) backOff(wall, if (costMs > budgetMs) "a summary took $costMs ms" else "the window keeps drawing")
    }

    fun windowGone(windowId: String) {
        shown.remove(windowId)
        caps.remove(windowId)
    }

    private fun record(window: WindowRef, before: Summarized, after: Summarized, byCap: Boolean, nowMono: Long) {
        val beforeKeys = before.summary.elements.mapNotNull { it.key }.toSet()
        val afterKeys = after.summary.elements.mapNotNull { it.key }.toSet()
        val appearing = after.summary.elements.withIndex().filter { (_, e) -> e.key != null && e.key !in beforeKeys }
        val going = before.summary.elements.withIndex().filter { (_, e) -> e.key != null && e.key !in afterKeys }

        val appeared = appearing.filter { (i, e) ->
            !scrolled(e.key!!, after, before) &&
                !(byCap && e.role == "progress") &&
                !(before.summary.omitted > 0 && i >= before.summary.elements.size - going.size)
        }.map { it.value }
        val gone = going.filter { (i, e) ->
            !scrolled(e.key!!, before, after) &&
                !(byCap && e.role == "progress") &&
                !(after.summary.omitted > 0 && i >= after.summary.elements.size - appearing.size)
        }.map { it.value }
        if (appeared.size + gone.size > burst) return

        val known = shown.getOrPut(window.windowId) { HashMap() }
        for (e in appeared) {
            val event = buffer.append { id, seq -> event(id, seq, nowMono, UiEventKind.ELEMENT_APPEARED, window, e) }
            known[e.key!!] = Shown(event.id, nowMono)
        }
        for (e in gone) {
            val first = known.remove(e.key!!)
            buffer.append { id, seq ->
                event(id, seq, nowMono, UiEventKind.ELEMENT_DISAPPEARED, window, e).copy(
                    durationMs = first?.let { nowMono - it.mono },
                    appearedId = first?.id,
                )
            }
        }
    }

    /** The element sits in a scroll container whose position changed between the two summaries. */
    private fun scrolled(key: String, where: Summarized, other: Summarized): Boolean =
        where.scrolledBy[key].orEmpty().any { c -> where.scrolls[c] != other.scrolls[c] }

    private fun backOff(wall: Long, why: String) {
        backOffUntil = wall + backOffMs
        buffer.append { id, seq ->
            UiEvent(
                id, seq, 0, wall, UiEventKind.GAP, UiSource.ELEMENT,
                gapFromMs = wall, gapToMs = backOffUntil,
                completeness = listOf(DegradationMarker("ui", "${DegradationCodes.UI_BACKED_OFF}: $why; elements inside windows are not compared for ${backOffMs / 1000} s")),
            )
        }
    }

    private fun event(id: String, seq: Long, mono: Long, kind: UiEventKind, window: WindowRef, e: SummaryElement) = UiEvent(
        id, seq, mono, wallClock(), kind, UiSource.ELEMENT,
        window = window,
        element = ElementRef(key = e.key!!, role = e.role, parent = parentOf(e.key!!)),
        content = ContentSummary(listOf(e.copy(key = null))),
        text = e.text ?: e.description,
    )

    private fun parentOf(key: String): String? = key.split('/').dropLast(1).lastOrNull()?.substringBefore('#')

    companion object {
        const val BURST = 6
        const val BUDGET_MS = 5L
        const val BACK_OFF_MS = 5_000L
        const val MAX_CAPS = 4
    }
}
