package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.NetworkQuery

/**
 * Feature 067, as of 069: what each live view (a plugin tab, a CLI watch) has been shown for its
 * current query (the rows of the pages it loaded, and the live entries that matched), so that an
 * entry that stops matching is told as left. Answers no longer pass over the whole history, so
 * nothing else is kept.
 */
internal class LiveViews(
    private val keepUpTo: Int = 250_000,
) {
    private class Shown(val query: NetworkQuery, val ids: LinkedHashSet<String>)

    private val views = HashMap<String, Shown>()

    /** The query as a view remembers it: paging and order do not change what matches. */
    fun key(query: NetworkQuery) = query.copy(limit = 0, before = null, order = io.yamsergey.dta.protocol.network.Order.NEWEST_FIRST)

    /** [ids] were shown to [view] for [query]: another query starts over, a next page adds to it. */
    @Synchronized
    fun shown(view: String, query: NetworkQuery, ids: Collection<String>) {
        val k = key(query)
        val current = views[view]?.takeIf { it.query == k } ?: Shown(k, LinkedHashSet()).also { views[view] = it }
        current.ids += ids
        trim(current)
    }

    /** A live decision: a new match is shown; one that no longer matches leaves. Returns whether it was shown. */
    @Synchronized
    fun decided(view: String, id: String, match: Boolean): Boolean {
        val set = views[view] ?: return false
        val had = id in set.ids
        if (match) { set.ids += id; trim(set) } else set.ids -= id
        return had
    }

    private fun trim(s: Shown) {
        val it = s.ids.iterator()
        while (s.ids.size > keepUpTo && it.hasNext()) { it.next(); it.remove() }
    }

    @Synchronized
    fun drop(view: String) { views.remove(view) }

    /** Every view whose name starts with [prefix] (a client that went away). */
    @Synchronized
    fun dropAll(prefix: String) { views.keys.removeIf { it.startsWith(prefix) } }

    @Synchronized
    fun size(): Int = views.size
}
