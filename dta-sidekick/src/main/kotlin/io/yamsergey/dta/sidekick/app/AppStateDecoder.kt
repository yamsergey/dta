package io.yamsergey.dta.sidekick.app

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.timeline.AppPayload
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.sidekick.timeline.HandlingScope
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/** Events per second, with a burst of the same size (feature 016, research R8). */
class TokenBucket(private val perSecond: Int = 200, private val clock: () -> Long) {
    private var tokens = perSecond.toDouble()
    private var last = clock()

    @Synchronized
    fun take(): Boolean {
        val now = clock()
        tokens = minOf(perSecond.toDouble(), tokens + (now - last) * perSecond / 1000.0)
        last = now
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}

/**
 * The app's calls through `DtaStateBridge.emit` (feature 016 US3, research R8), as timeline
 * events: facets, events with declared causes, causes declared afterwards, and scopes. Local
 * ids are mapped to timeline ids; a cause DTA never saw keeps an unknown far end. Beyond the
 * rate, events are dropped and the drop is stated as a gap (constitution III).
 */
class AppStateDecoder(
    private val buffer: TimelineBuffer,
    private val facets: FacetState,
    private val scope: HandlingScope,
    private val mono: () -> Long,
    private val wall: () -> Long,
    private val rate: TokenBucket = TokenBucket(clock = mono),
) {
    private val ids = object : LinkedHashMap<Long, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > KEPT
    }
    private var dropped = 0
    private var droppedFrom = 0L

    var calls = 0L
        private set

    @Synchronized
    fun emit(op: Int, a: String?, b: String?, attrs: Array<String>?, id: Long, cause: Long) {
        calls++
        when (op) {
            FACET -> a?.let { facet(it, b) }
            EVENT -> if (a != null && admit()) event(a, attrs, id, cause)
            CAUSE -> if (admit()) declared(to = id, from = cause)
            SCOPE_ENTER -> ids[id]?.let(scope::enterDeclared)
            SCOPE_EXIT -> if (ids.containsKey(id)) scope.exit()
        }
    }

    private fun facet(name: String, value: String?) {
        val facet = Facets.app(name.take(Bounds.NAME))
        val change = facets.change(facet, value, null) ?: return
        if (!admit()) return
        val e = buffer.append { tid, seq ->
            TimelineEvent(
                tid, seq, mono(), wall(), Sources.APP, Kinds.FACET_CHANGED,
                title = "$facet = ${change.value ?: "—"}",
                facets = listOf(change),
                causes = listOfNotNull(scope.current()),
                completeness = if (change.cut) listOf(DegradationMarker("app", "${DegradationCodes.TIMELINE_VALUE_CUT}: $facet")) else emptyList(),
            )
        }
        facets.attribute(listOf(facet), e.id)
    }

    private fun event(name: String, attrs: Array<String>?, id: Long, cause: Long) {
        val pairs = attrs.orEmpty().toList().chunked(2).filter { it.size == 2 }.take(Bounds.ATTRS).associate { (k, v) -> k to v }
        val e = buffer.append { tid, seq ->
            TimelineEvent(
                tid, seq, mono(), wall(), Sources.APP, Kinds.app(name),
                title = name + pairs.entries.joinToString("") { " ${it.key}=${it.value}" },
                app = AppPayload(name, pairs, id),
                causes = listOfNotNull(scope.current(), cause.takeIf { it != 0L }?.let(::link)),
            )
        }
        if (id != 0L) ids[id] = e.id
    }

    private fun declared(to: Long, from: Long) {
        val effect = ids[to] ?: return
        buffer.append { tid, seq ->
            TimelineEvent(
                tid, seq, mono(), wall(), Sources.APP, Kinds.DECLARED_CAUSE,
                title = "cause declared",
                app = AppPayload("cause", mapOf("to" to effect)),
                causes = listOf(link(from)),
            )
        }
    }

    private fun link(local: Long): CauseLink {
        val known = ids[local]
        return CauseLink(known ?: "local:$local", Basis.DECLARED, CauseRules.APP_DECLARED, declaredBy = Sources.APP, fromUnknown = known == null)
    }

    /** Takes a token, or counts a drop; the first event after drops is preceded by their gap. */
    private fun admit(): Boolean {
        if (!rate.take()) {
            if (dropped == 0) droppedFrom = wall()
            dropped++
            return false
        }
        if (dropped > 0) {
            val count = dropped
            dropped = 0
            buffer.append { tid, seq ->
                TimelineEvent(
                    tid, seq, mono(), wall(), Sources.TIMELINE, Kinds.GAP,
                    gapCount = count, gapFromMs = droppedFrom, gapToMs = wall(), gapSource = Sources.APP,
                    completeness = listOf(DegradationMarker("app", "${DegradationCodes.TIMELINE_RATE_DROPPED}: $count of the app's events over its rate")),
                )
            }
        }
        return true
    }

    companion object {
        const val FACET = 1
        const val EVENT = 2
        const val CAUSE = 3
        const val SCOPE_ENTER = 4
        const val SCOPE_EXIT = 5
        private const val KEPT = 4_096
    }
}
