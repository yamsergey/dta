package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.EventLink
import io.yamsergey.dta.protocol.ui.LinkRelation

/**
 * The latest HTTP exchanges, fed by the network recorder, for linking UI events to the traffic
 * around them (feature 015, research R9): in flight at the moment, or finished within
 * [windowMs] before it. The relation is time only; causes are the state graph's business.
 */
class RecentNetwork(private val windowMs: Long = 2_000, private val capacity: Int = 64) {
    private class Exchange(val id: String, val startMs: Long, var endMs: Long? = null)

    private val exchanges = ArrayDeque<Exchange>()

    @Synchronized
    fun started(id: String, atMs: Long) {
        if (exchanges.any { it.id == id }) return
        exchanges.addLast(Exchange(id, atMs))
        if (exchanges.size > capacity) exchanges.removeFirst()
    }

    @Synchronized
    fun finished(id: String, atMs: Long) {
        exchanges.firstOrNull { it.id == id }?.endMs = atMs
    }

    @Synchronized
    fun links(now: Long): List<EventLink> = exchanges.mapNotNull { e ->
        val end = e.endMs
        when {
            e.startMs > now -> null
            end == null || end > now -> EventLink("network", e.id, LinkRelation.IN_FLIGHT)
            now - end <= windowMs -> EventLink("network", e.id, LinkRelation.FINISHED_BEFORE)
            else -> null
        }
    }
}
