package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import io.yamsergey.dta.protocol.timeline.Cut
import io.yamsergey.dta.protocol.timeline.CutReason
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent

/**
 * Causes and effects over the kept timeline (feature 016, research R11). Exact and declared
 * links come from the events themselves; inferred candidates only when asked for. Where a
 * chain cannot go on — the event is gone, never known, beyond the depth, from another process
 * — it says so (constitution III). A declared link that points forward in time is rejected.
 */
class CauseGraph(
    events: List<TimelineEvent>,
    private val inferredRules: InferredRules = InferredRules(events),
) {
    class UnknownEvent(val id: String, val evicted: Boolean) :
        RuntimeException(if (evicted) "event $id is no longer kept (older events were dropped)" else "no event $id in this app's timeline")

    private val byId = events.associateBy { it.id }
    private val order = events.withIndex().associate { (i, e) -> e.id to i }
    private val oldestSeq = events.filter { it.seq > 0 }.groupBy { epoch(it.id) }.mapValues { (_, es) -> es.minOf { it.seq } }

    /** Kept links per effect, rejected ones stated on the event instead. */
    private val causes: Map<String, List<CauseLink>>
    private val rejected: Map<String, List<DegradationMarker>>

    init {
        val kept = HashMap<String, List<CauseLink>>()
        val rejects = HashMap<String, List<DegradationMarker>>()
        for (e in events) {
            val (ok, bad) = e.causes.partition { l -> forwardOk(l, e) }
            kept[e.id] = ok
            if (bad.isNotEmpty()) {
                rejects[e.id] = bad.map { DegradationMarker("timeline", "${DegradationCodes.TIMELINE_REJECTED_CAUSE}: the cause ${it.from} comes after this event") }
            }
        }
        causes = kept
        rejected = rejects
    }

    private val effects: Map<String, List<Pair<String, CauseLink>>> =
        causes.flatMap { (to, links) -> links.map { it.from to (to to it) } }.groupBy({ it.first }, { it.second })

    private val inferredCache = HashMap<String, List<CauseLink>>()

    private fun inferred(e: TimelineEvent): List<CauseLink> =
        inferredCache.getOrPut(e.id) { if (known(e)) emptyList() else inferredRules.candidates(e) }

    private val inferredEffects: Map<String, List<Pair<String, CauseLink>>> by lazy {
        byId.values.flatMap { e -> inferred(e).map { it.from to (e.id to it) } }.groupBy({ it.first }, { it.second })
    }

    /** The event has an exact or declared cause, or is a root (the user's input, the timeline's own). */
    private fun known(e: TimelineEvent) = e.source == Sources.INPUT || e.source == Sources.TIMELINE || causes[e.id].orEmpty().isNotEmpty()

    fun walk(id: String, direction: ChainDirection, depth: Int = DEFAULT_DEPTH, inferred: Boolean = false): CauseChain {
        val root = byId[id] ?: throw UnknownEvent(id, evicted(id))
        val maxDepth = depth.coerceIn(1, MAX_DEPTH)
        val nodes = LinkedHashMap<String, TimelineEvent>().apply { put(root.id, root) }
        val links = ArrayList<ChainLink>()
        val cuts = ArrayList<Cut>()
        var frontier = listOf(root)
        var level = 0
        while (frontier.isNotEmpty()) {
            val next = ArrayList<TimelineEvent>()
            for (node in frontier) {
                val edges = edges(node, direction, inferred)
                if (edges.isEmpty()) continue
                if (level >= maxDepth) {
                    cuts += Cut(node.id, CutReason.DEPTH, "${edges.size} more links beyond depth $maxDepth")
                    continue
                }
                for ((otherId, link) in edges) {
                    val other = byId[otherId]
                    val cut = when {
                        link.fromUnknown -> CutReason.UNKNOWN_EVENT
                        other == null -> if (evicted(otherId)) CutReason.EVICTED else CutReason.UNKNOWN_EVENT
                        epoch(otherId) != epoch(node.id) -> CutReason.PROCESS_BOUNDARY
                        else -> null
                    }
                    if (cut != null) {
                        cuts += Cut(node.id, cut, otherId)
                        continue
                    }
                    links += if (direction == ChainDirection.CAUSES) ChainLink(otherId, node.id, link) else ChainLink(node.id, otherId, link)
                    if (nodes.putIfAbsent(otherId, other!!) == null) next += other
                }
            }
            frontier = next
            level++
        }
        val all = nodes.values.toList()
        val unknown = all.filter { !known(it) }
        return CauseChain(
            root = root.copy(completeness = root.completeness + rejected[root.id].orEmpty()),
            direction = direction,
            nodes = all,
            links = links,
            noKnownCause = unknown.map { it.id },
            inferredCandidates = unknown.associate { it.id to inferred(it).size }.filterValues { it > 0 },
            cuts = cuts,
            inferredIncluded = inferred,
        )
    }

    /** The causes the chain knows for [e]: exact and declared, and inferred ones when asked. */
    fun causesOf(e: TimelineEvent, inferred: Boolean): List<CauseLink> =
        causes[e.id].orEmpty() + if (inferred) inferred(e) else emptyList()

    fun hasKnownCause(e: TimelineEvent) = known(e)

    fun inferredCount(e: TimelineEvent) = if (known(e)) 0 else inferred(e).size

    private fun edges(node: TimelineEvent, direction: ChainDirection, inferred: Boolean): List<Pair<String, CauseLink>> =
        when (direction) {
            ChainDirection.CAUSES -> causesOf(node, inferred).map { it.from to it }
            ChainDirection.EFFECTS -> effects[node.id].orEmpty() + if (inferred) inferredEffects[node.id].orEmpty() else emptyList()
        }

    private fun forwardOk(link: CauseLink, effect: TimelineEvent): Boolean {
        if (link.basis != Basis.DECLARED) return true
        val from = order[link.from] ?: return true
        return from < order.getValue(effect.id)
    }

    private fun evicted(id: String): Boolean {
        val seq = id.substringAfter(':').toLongOrNull() ?: return false
        val oldest = oldestSeq[epoch(id)] ?: return false
        return seq < oldest
    }

    private fun epoch(id: String) = id.substringBefore(':')

    companion object {
        const val DEFAULT_DEPTH = 8
        const val MAX_DEPTH = 32
    }
}
