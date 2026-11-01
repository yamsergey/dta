package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.GraphWindow
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.StateGraph
import io.yamsergey.dta.protocol.timeline.StateNode
import io.yamsergey.dta.protocol.timeline.StateRef
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.Transition

/**
 * States and transitions of one process (feature 016, research R12): a state is the values of
 * the chosen facets; a transition is a change of them, triggered by the event that carried the
 * change. Regrouping by other facets recomputes over the kept events, nothing is re-recorded.
 * An app event that changes none of the chosen values is a self-loop.
 */
class StateGrouper(
    private val events: List<TimelineEvent>,
    /** Facet values at the start of [events]. */
    private val initial: List<FacetValue>,
    /** Facets some source reports (now or in [events]). */
    private val available: Set<String>,
    private val partial: Boolean = false,
    private val causesOf: (TimelineEvent) -> List<CauseLink> = { it.causes },
    private val known: (TimelineEvent) -> Boolean = { it.source == Sources.INPUT || it.source == Sources.TIMELINE || it.causes.isNotEmpty() },
) {
    private class Visit(val values: List<String?>, val enterMono: Long, val trigger: TimelineEvent?)

    fun graph(chosen: List<String>?, minHoldMs: Long = 0): StateGraph {
        val wanted = chosen?.distinct() ?: available.filter { it !in Facets.NOT_DEFAULT_IN_GRAPH }.sorted()
        val unavailable = wanted.filter { it !in available }
        val facets = wanted - unavailable.toSet()
        val notes = mutableListOf<DegradationMarker>()
        if (unavailable.isNotEmpty()) notes += DegradationMarker("state", "${DegradationCodes.STATE_FACET_UNAVAILABLE}: ${unavailable.joinToString()}")
        if (partial) notes += DegradationMarker("state", "${DegradationCodes.STATE_WINDOW_PARTIAL}: states before the oldest kept event are missing")

        val start = events.firstOrNull()?.monoMs ?: 0L
        val end = events.lastOrNull()?.monoMs ?: start
        val values = HashMap<String, String?>().apply { initial.forEach { put(it.name, it.value) } }
        fun tuple() = facets.map { values[it] }

        var visits = mutableListOf(Visit(tuple(), start, null))
        val selfLoops = ArrayList<Pair<Int, TimelineEvent>>()
        for (e in events) {
            if (e.facets.isEmpty() && e.source != Sources.APP) continue
            val before = tuple()
            e.facets.forEach { values[it.name] = it.value }
            val after = tuple()
            when {
                after != before -> visits += Visit(after, e.monoMs, e)
                e.source == Sources.APP -> selfLoops += visits.lastIndex to e
            }
        }

        var merged = 0
        if (minHoldMs > 0) {
            val kept = mutableListOf<Visit>()
            for ((i, v) in visits.withIndex()) {
                val held = (visits.getOrNull(i + 1)?.enterMono ?: end) - v.enterMono
                if (i < visits.lastIndex && held < minHoldMs) {
                    merged++
                    continue
                }
                if (kept.lastOrNull()?.values == v.values) continue
                kept += v
            }
            visits = kept
            if (merged > 0) notes += DegradationMarker("state", "${DegradationCodes.STATE_SHORT_MERGED}: $merged states held under $minHoldMs ms")
        }

        val refs = LinkedHashMap<List<String?>, StateRef>()
        fun ref(v: List<String?>) = refs.getOrPut(v) { stateRef(facets, v) }
        val entries = HashMap<List<String?>, Int>()
        val held = HashMap<List<String?>, Long>()
        visits.forEachIndexed { i, v ->
            ref(v.values)
            entries.merge(v.values, 1, Int::plus)
            held.merge(v.values, (visits.getOrNull(i + 1)?.enterMono ?: end) - v.enterMono, Long::plus)
        }

        val edges = LinkedHashMap<Triple<String, String, Boolean>, MutableList<TimelineEvent>>()
        for (i in 1..visits.lastIndex) {
            edges.getOrPut(Triple(ref(visits[i - 1].values).id, ref(visits[i].values).id, false)) { mutableListOf() } += visits[i].trigger!!
        }
        if (minHoldMs == 0L) {
            for ((at, e) in selfLoops) {
                val id = ref(visits[at].values).id
                edges.getOrPut(Triple(id, id, true)) { mutableListOf() } += e
            }
        }
        val transitions = edges.map { (key, triggers) ->
            val latest = triggers.last()
            Transition(
                from = key.first,
                to = key.second,
                count = triggers.size,
                triggers = triggers.asReversed().take(MAX_TRIGGERS).map { it.id },
                causes = causesOf(latest),
                noKnownCause = !known(latest),
                selfLoop = key.third,
            )
        }
        val current = visits.last().values
        return StateGraph(
            facets = facets,
            unavailable = unavailable,
            nodes = refs.map { (v, r) -> StateNode(r, entries[v] ?: 0, held[v] ?: 0, current = v == current) },
            transitions = transitions,
            current = ref(current).id,
            window = GraphWindow(start, end, partial),
            merged = merged,
            notes = notes,
        )
    }

    companion object {
        const val MAX_TRIGGERS = 5

        /** The facet values at the start of [events]: a value's first change says what it was before. */
        fun initialValues(events: List<TimelineEvent>, current: List<FacetValue>): List<FacetValue> {
            val firstChange = LinkedHashMap<String, String?>()
            for (e in events) for (c in e.facets) if (c.name !in firstChange) firstChange[c.name] = c.previous
            val byName = current.associateBy { it.name }.toMutableMap()
            for ((name, before) in firstChange) {
                byName[name] = (byName[name] ?: FacetValue(name, null, Facets.sourceOf(name), 0, 0)).copy(value = before)
            }
            return byName.values.toList()
        }

        fun stateRef(facets: List<String>, values: List<String?>): StateRef {
            val pairs = facets.zip(values)
            val key = pairs.joinToString("\u0000") { (k, v) -> "$k=${v ?: ""}" }
            var hash = 0x811c9dc5.toInt()
            for (b in key.toByteArray()) hash = (hash xor (b.toInt() and 0xff)) * 0x01000193
            return StateRef(
                id = "s" + Integer.toHexString(hash).padStart(8, '0'),
                values = pairs.toMap(),
                label = values.joinToString(" · ") { it ?: "—" },
            )
        }
    }
}
