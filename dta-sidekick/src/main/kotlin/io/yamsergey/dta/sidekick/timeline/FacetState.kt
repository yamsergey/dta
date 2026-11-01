package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.Facets

/**
 * The current value of every facet the sidekick's sources report (feature 016, research R10).
 * A change is reported only when the value differs; values are cut at [Bounds.FACET_VALUE].
 * The snapshot goes to the host with every read, so the current state survives eviction.
 */
class FacetState(
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
    private val wall: () -> Long = { System.currentTimeMillis() },
) {
    private val values = LinkedHashMap<String, FacetValue>()

    @Synchronized
    fun change(name: String, value: String?, changedBy: String?): FacetChange? {
        val cut = value != null && value.length > Bounds.FACET_VALUE
        val kept = if (cut) value!!.take(Bounds.FACET_VALUE) else value
        val before = values[name]
        if (before != null && before.value == kept) return null
        if (before == null && kept == null) return null
        values[name] = FacetValue(name, kept, Facets.sourceOf(name), clock(), wall(), changedBy, cut = cut)
        return FacetChange(name, kept, previous = before?.value, cut = cut)
    }

    /** Fills in the event that made a change once its id is known. */
    @Synchronized
    fun attribute(names: Collection<String>, eventId: String) {
        for (name in names) values[name]?.takeIf { it.changedBy == null }?.let { values[name] = it.copy(changedBy = eventId) }
    }

    @Synchronized
    fun snapshot(): List<FacetValue> = values.values.toList()
}
