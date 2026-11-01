package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.TimelineEvent

/** What the Timeline tab can be told (feature 017). */
sealed interface TimelineChange {
    data class Loaded(val events: List<TimelineEvent>) : TimelineChange
    data class Arrived(val event: TimelineEvent) : TimelineChange
    data class Sources(val sources: List<SourceStatus>) : TimelineChange
    data class State(val current: List<FacetValue>) : TimelineChange
    data class Select(val id: String?) : TimelineChange
    data class Filter(val filter: TimelineFilter) : TimelineChange
    /** [factor] < 1 zooms in; the moment [anchorMs] stays where it is on screen. */
    data class Zoom(val factor: Double, val anchorMs: Long) : TimelineChange
    data class Pan(val deltaMs: Long) : TimelineChange
    data class Follow(val on: Boolean) : TimelineChange
    data object Reset : TimelineChange
}

/** The time span in view: its right edge, the scale, and whether it follows the newest event. */
data class ViewWindow(val endMs: Long = 0, val msPerPx: Double = DEFAULT_MS_PER_PX, val follow: Boolean = true) {
    companion object {
        const val DEFAULT_MS_PER_PX = 10.0
        const val MIN_MS_PER_PX = 0.1
        const val MAX_MS_PER_PX = 60_000.0
        /** Room right of the newest event while following, in pixels. */
        const val MARGIN_PX = 40
    }
}

/**
 * The Timeline tab's state (feature 017, like 009's live list): events merged by id and kept in
 * time order, within [bound]; the sources and current facets; the filter, the selection and the
 * window in view. Pure; the plugin only reduces changes into it and draws it.
 */
data class TimelineView(
    val events: List<TimelineEvent> = emptyList(),
    val sources: List<SourceStatus> = emptyList(),
    val current: List<FacetValue> = emptyList(),
    val filter: TimelineFilter = TimelineFilter(),
    val selectedId: String? = null,
    /** The selected event is no longer kept here. */
    val selectedGone: Boolean = false,
    val window: ViewWindow = ViewWindow(),
    val bound: Int = BOUND,
    /** Older events were dropped here to stay within [bound]. */
    val dropped: Int = 0,
) {
    /** The events the filter lets through, in time order. */
    val shown: List<TimelineEvent> get() = if (filter.active) events.filter(filter::matches) else events

    val selected: TimelineEvent? get() = selectedId?.let { id -> events.firstOrNull { it.id == id } }

    fun reduce(change: TimelineChange): TimelineView = when (change) {
        is TimelineChange.Loaded -> merge(change.events)
        is TimelineChange.Arrived -> merge(listOf(change.event))
        is TimelineChange.Sources -> copy(sources = change.sources)
        is TimelineChange.State -> copy(current = change.current)
        is TimelineChange.Select -> copy(selectedId = change.id, selectedGone = false)
        is TimelineChange.Filter -> copy(filter = change.filter)
        is TimelineChange.Zoom -> {
            val scale = (window.msPerPx * change.factor).coerceIn(ViewWindow.MIN_MS_PER_PX, ViewWindow.MAX_MS_PER_PX)
            val actual = scale / window.msPerPx
            copy(window = window.copy(msPerPx = scale, endMs = change.anchorMs + ((window.endMs - change.anchorMs) * actual).toLong()))
        }
        is TimelineChange.Pan -> copy(window = window.copy(endMs = window.endMs + change.deltaMs, follow = false))
        is TimelineChange.Follow -> if (change.on) copy(window = window.copy(follow = true)).followed() else copy(window = window.copy(follow = false))
        TimelineChange.Reset -> TimelineView(bound = bound)
    }

    private fun merge(incoming: List<TimelineEvent>): TimelineView {
        if (incoming.isEmpty()) return this
        val byId = LinkedHashMap<String, TimelineEvent>(events.size + incoming.size)
        events.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        val sorted = byId.values.sortedWith(ORDER)
        val over = (sorted.size - bound).coerceAtLeast(0)
        val kept = if (over > 0) sorted.drop(over) else sorted
        val gone = selectedId != null && (selectedGone || kept.none { it.id == selectedId })
        return copy(events = kept, dropped = dropped + over, selectedGone = gone).let { if (it.window.follow) it.followed() else it }
    }

    private fun followed(): TimelineView {
        val newest = events.maxOfOrNull { it.wallMs } ?: return this
        return copy(window = window.copy(endMs = newest + (ViewWindow.MARGIN_PX * window.msPerPx).toLong()))
    }

    companion object {
        const val BOUND = 10_000

        /** Wall time, then the order within a run (seq), then id. */
        val ORDER: Comparator<TimelineEvent> = compareBy<TimelineEvent>({ it.wallMs }, { it.seq }, { it.id })
    }
}
