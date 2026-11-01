package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.TimelineEvent

/**
 * What the app was in at an event's moment (feature 017, research R5): the run's facet values
 * at its first kept event, then its facet changes up to the event. Today's values stand in
 * only for facets that never changed, and only in the latest run. Partial when the run's first
 * events are no longer kept.
 */
object FacetsAt {
    data class Result(val values: List<FacetValue>, val partial: Boolean)

    fun of(events: List<TimelineEvent>, current: List<FacetValue>, atId: String): Result {
        val epoch = atId.substringBefore(':')
        val run = events.filter { it.id.substringBefore(':') == epoch }
        val latest = events.lastOrNull { it.seq > 0 }?.id?.substringBefore(':') == epoch
        val values = LinkedHashMap<String, FacetValue>()
        Facets.initialValues(run, if (latest) current else emptyList()).forEach { values[it.name] = it }
        for (e in run) {
            for (c in e.facets) values[c.name] = FacetValue(c.name, c.value, Facets.sourceOf(c.name), e.monoMs, e.wallMs, e.id, cut = c.cut)
            if (e.id == atId) break
        }
        val partial = (run.firstOrNull { it.seq > 0 }?.seq ?: 1) > 1
        return Result(values.values.filter { it.value != null }, partial)
    }
}
