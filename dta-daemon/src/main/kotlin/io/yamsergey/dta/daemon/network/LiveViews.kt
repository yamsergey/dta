package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.QueryNarrowing

/**
 * Feature 067: what each live view (a plugin tab, a CLI watch) last matched, so the next pass
 * reads only what can match — the same query again, one of its last few queries, or a narrower one
 * — and a full pass is needed only otherwise.
 *
 * A match set is valid for the live history it was made from (its epoch and removals); an entry
 * that changed since (new record, body copied) or that a pass reads only in part (a body not
 * here whole as text: the counts a pass states depend on the query) is read again.
 */
internal class LiveViews(
    private val perView: Int = 4,
    private val total: Int = 16,
    private val keepUpTo: Int = 250_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class MatchSet(val query: NetworkQuery, val epochId: String?, val seq: Long, val removals: Long, val ids: MutableSet<String>, var usedMs: Long)

    /** How a pass was made, for tests and the measurement. */
    enum class Kind { FULL, SAME, RECENT, NARROWED }

    private val views = HashMap<String, ArrayDeque<MatchSet>>()

    /** The query as a set remembers it: paging and order do not change what matches. */
    fun key(query: NetworkQuery) = query.copy(limit = 0, before = null, order = io.yamsergey.dta.protocol.network.Order.NEWEST_FIRST)

    /** The entries a pass of [query] for [view] must read, or null for all of them. */
    @Synchronized
    fun candidates(view: String, query: NetworkQuery, state: HostIndex.LiveState): Pair<Kind, ((NetworkEntry) -> Boolean)?> {
        val sets = views[view] ?: return Kind.FULL to null
        val k = key(query)
        val valid = sets.filter { it.epochId == state.epochId && it.removals == state.removals }
        val (kind, base) = valid.firstOrNull { it.query == k }?.let { (if (it === sets.first()) Kind.SAME else Kind.RECENT) to it }
            ?: valid.filter { QueryNarrowing.narrows(it.query, k) }.minByOrNull { it.ids.size }?.let { Kind.NARROWED to it }
            ?: return Kind.FULL to null
        base.usedMs = clock()
        val changed = state.changedSince(base.seq)
        // A copy of the ids: live decisions change the set while the pass reads it.
        val ids = HashSet(base.ids)
        return kind to { e -> e.id in ids || changed(e) || state.partial(e) }
    }

    /** Remembers what [query] matched for [view] as its current set. */
    @Synchronized
    fun record(view: String, query: NetworkQuery, state: HostIndex.LiveState, ids: Set<String>) {
        val sets = views.getOrPut(view) { ArrayDeque() }
        val k = key(query)
        sets.removeAll { it.query == k || it.epochId != state.epochId || it.removals != state.removals }
        if (ids.size > keepUpTo) return
        sets.addFirst(MatchSet(k, state.epochId, state.seq, state.removals, HashSet(ids), clock()))
        while (sets.size > perView) sets.removeLast()
        // At most [total] sets in all: the least recently used go first.
        while (views.values.sumOf { it.size } > total) {
            val (owner, oldest) = views.flatMap { (v, s) -> s.map { v to it } }.minBy { it.second.usedMs }
            views[owner]!!.remove(oldest)
        }
        views.entries.removeIf { it.value.isEmpty() }
    }

    /** The view's current set: the entries a live event decides against (`left` when one stops matching). */
    @Synchronized
    fun current(view: String): MatchSet? = views[view]?.firstOrNull()

    /** A live decision keeps the view's current set up to date (a new match, or one that left). */
    @Synchronized
    fun decided(view: String, id: String, match: Boolean): Boolean {
        val set = views[view]?.firstOrNull() ?: return false
        val had = id in set.ids
        if (match) set.ids += id else set.ids -= id
        return had
    }

    @Synchronized
    fun drop(view: String) { views.remove(view) }

    /** Every view whose name starts with [prefix] (a client that went away). */
    @Synchronized
    fun dropAll(prefix: String) { views.keys.removeIf { it.startsWith(prefix) } }

    @Synchronized
    fun size(): Int = views.values.sumOf { it.size }
}
