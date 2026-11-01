package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * The UI journal's way into the timeline (features 015/016, research R1): the recorders build
 * 015's [UiEvent]; this wraps each in the timeline envelope, with the facet changes it made and
 * its causes — a disappearance is caused by its appearance (`same-window`, exact), anything
 * recorded while a click or back is handled by that input ([scope]).
 */
class UiJournalBuffer(
    private val timeline: TimelineBuffer,
    /** The cause from the handling scope open on this thread right now (016 US7), if any. */
    private val scope: () -> CauseLink? = { null },
) {
    /** A journal of its own (tests): a timeline that pushes the UI events it wraps. */
    constructor(capacity: Int = TimelineBuffer.CAPACITY, epoch: Long, publish: (UiEvent) -> Unit) :
        this(TimelineBuffer(capacity, epoch) { e -> e.ui?.let(publish) })

    /** Appends the UI event [make] builds, wrapped, with [facets] changed by it; returns the UI event. */
    fun append(facets: () -> List<FacetChange> = { emptyList() }, make: (id: String, seq: Long) -> UiEvent): UiEvent {
        var ui: UiEvent? = null
        timeline.append { id, seq ->
            val event = make(id, seq)
            ui = event
            wrap(event, facets(), scope())
        }
        return ui!!
    }

    companion object {
        fun wrap(ui: UiEvent, facets: List<FacetChange> = emptyList(), scope: CauseLink? = null): TimelineEvent {
            val causes = buildList {
                scope?.let(::add)
                ui.appearedId?.let { add(CauseLink(it, Basis.EXACT, CauseRules.SAME_WINDOW)) }
            }
            val gap = ui.kind == UiEventKind.GAP
            return TimelineEvent(
                id = ui.id,
                seq = ui.seq,
                monoMs = ui.monoMs.takeIf { it > 0 } ?: android.os.SystemClock.uptimeMillis(),
                wallMs = ui.wallMs,
                source = if (gap) Sources.TIMELINE else Sources.UI,
                kind = if (gap) Kinds.GAP else Kinds.ui(kindName(ui.kind)),
                ui = ui,
                facets = facets,
                causes = causes,
                gapCount = ui.gapCount,
                gapFromMs = ui.gapFromMs,
                gapToMs = ui.gapToMs,
                gapSource = if (gap) Sources.UI else null,
                completeness = ui.completeness,
            )
        }

        fun kindName(kind: UiEventKind): String = UiEventKind.serializer().descriptor.getElementName(kind.ordinal)
    }
}
