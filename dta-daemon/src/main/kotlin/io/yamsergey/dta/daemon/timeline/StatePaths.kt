package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.StateGraph
import io.yamsergey.dta.protocol.timeline.StatePath
import io.yamsergey.dta.protocol.timeline.Transition

/** The shortest path the app was seen to take between two states of a [StateGraph] (feature 016). */
class StatePaths(private val graph: StateGraph) {
    /** [from] and [to] are state ids or labels; [to] is the current state when null. */
    fun path(from: String, to: String? = null): StatePath {
        val start = resolve(from)
        val target = if (to != null) resolve(to) else graph.current
        if (start == null || target == null) return StatePath(from, to ?: graph.current.orEmpty(), emptyList(), found = false)
        val out = graph.transitions.filter { !it.selfLoop }.groupBy { it.from }
        val via = HashMap<String, Transition>()
        val seen = hashSetOf(start)
        var frontier = listOf(start)
        while (frontier.isNotEmpty() && target !in seen) {
            frontier = frontier.flatMap { s ->
                out[s].orEmpty().filter { seen.add(it.to) }.onEach { via[it.to] = it }.map { it.to }
            }
        }
        if (target !in seen) return StatePath(start, target, emptyList(), found = false)
        val steps = generateSequence(via[target]) { t -> via[t.from].takeIf { t.from != start } }.toList().asReversed()
        return StatePath(start, target, steps, found = true)
    }

    private fun resolve(key: String): String? =
        graph.nodes.firstOrNull { it.state.id == key }?.state?.id
            ?: graph.nodes.firstOrNull { it.state.label.equals(key, ignoreCase = true) }?.state?.id
}
