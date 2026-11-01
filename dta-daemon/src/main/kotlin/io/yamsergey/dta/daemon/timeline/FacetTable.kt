package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.CurrentState
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Stale
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState

/**
 * The current value of every facet of one app on the host (feature 016, research R10). Kept
 * apart from the timeline so the current state survives eviction. A process end makes every
 * value stale; a new process's snapshot replaces the values it has (constitution III).
 */
class FacetTable {
    private val values = LinkedHashMap<String, FacetValue>()
    private var epoch: Long? = null

    /** The sidekick's snapshot with a read: its values are current for [epoch]. */
    @Synchronized
    fun seed(epoch: Long, snapshot: List<FacetValue>) {
        this.epoch = epoch
        for (v in snapshot) {
            val known = values[v.name]
            if (known == null || known.stale != null || known.changedMonoMs <= v.changedMonoMs) values[v.name] = v.copy(stale = null)
        }
    }

    @Synchronized
    fun apply(event: TimelineEvent) {
        for (c in event.facets) {
            if (c.value == null) {
                values.remove(c.name)
                continue
            }
            values[c.name] = FacetValue(c.name, c.value, Facets.sourceOf(c.name), event.monoMs, event.wallMs, event.id, cut = c.cut)
        }
    }

    @Synchronized
    fun processEnded(atWallMs: Long) {
        for ((name, v) in values) if (v.stale == null) values[name] = v.copy(stale = Stale(atWallMs, PROCESS_ENDED))
    }

    @Synchronized
    fun current(sources: List<SourceStatus>): CurrentState {
        val off = sources.filter { it.state == SourceState.OFF }.associateBy { it.name }
        val facets = values.values.map { v ->
            val source = off[v.source]
            if (v.stale == null && source != null) v.copy(stale = Stale(v.changedWallMs, "source off: ${source.reason ?: "no reason given"}")) else v
        }
        return CurrentState(facets, sources)
    }

    @Synchronized
    fun snapshot(): List<FacetValue> = values.values.toList()

    companion object {
        const val PROCESS_ENDED = "process-ended"
    }
}
