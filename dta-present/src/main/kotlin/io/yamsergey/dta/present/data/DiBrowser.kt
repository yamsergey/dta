package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.DiBinding
import io.yamsergey.dta.protocol.data.DiReport

/**
 * Feature 037: a DI report as nodes and edges for the plugin — each binding with its graph, who it
 * depends on and who uses it (the reverse), a node's neighbourhood laid out in columns, and the
 * whole graph in layers by dependency depth. Pure.
 */
class DiBrowser(val report: DiReport) {
    data class Node(val id: String, val graph: String, val framework: String, val binding: DiBinding) {
        val title: String get() = binding.type.substringBefore('<').substringAfterLast('.').ifEmpty { binding.name }
        val state: String get() = listOfNotNull(
            binding.kind,
            when {
                binding.runtimeType != null -> "created"
                binding.kind == "factory" -> "a new one each time"
                else -> "not created yet"
            },
        ).joinToString(" · ")
    }

    /** Columns left to right and edges (from, to) where `from` depends on `to`. */
    data class Layout(val columns: List<List<String>>, val edges: List<Pair<String, String>>)

    val nodes: List<Node> = report.graphs.flatMapIndexed { gi, g ->
        g.bindings.mapIndexed { bi, b -> Node(b.id ?: "$gi.$bi", g.name, g.framework, b) }
    }
    private val byId = nodes.associateBy { it.id }
    private val users: Map<String, List<String>> = nodes.flatMap { n -> n.binding.dependsOn.map { it to n.id } }.groupBy({ it.first }, { it.second })

    fun node(id: String): Node? = byId[id]
    fun dependsOn(id: String): List<Node> = byId[id]?.binding?.dependsOn.orEmpty().mapNotNull(byId::get)
    fun usedBy(id: String): List<Node> = users[id].orEmpty().mapNotNull(byId::get)

    /** [id], its dependencies [depth] levels to the left and its dependents [depth] levels to the right. */
    fun neighbourhood(id: String, depth: Int = 2): Layout {
        val left = mutableListOf<List<String>>()
        val right = mutableListOf<List<String>>()
        val seen = mutableSetOf(id)
        var frontier = listOf(id)
        repeat(depth) { frontier = frontier.flatMap { f -> dependsOn(f).map { it.id } }.distinct().filter(seen::add); if (frontier.isNotEmpty()) left.add(0, frontier) }
        frontier = listOf(id)
        repeat(depth) { frontier = frontier.flatMap { f -> usedBy(f).map { it.id } }.distinct().filter(seen::add); if (frontier.isNotEmpty()) right.add(frontier) }
        val shown = seen
        return Layout(left + listOf(listOf(id)) + right, edges(shown))
    }

    /** Every binding, in layers: those depending on nothing first, each other one right of all it depends on. */
    fun whole(): Layout {
        val level = HashMap<String, Int>()
        fun levelOf(id: String, path: Set<String>): Int = level[id] ?: run {
            val deps = byId[id]?.binding?.dependsOn.orEmpty().filter { it in byId && it !in path }
            (if (deps.isEmpty()) 0 else 1 + deps.maxOf { levelOf(it, path + id) }).also { level[id] = it }
        }
        nodes.forEach { levelOf(it.id, emptySet()) }
        val columns = nodes.groupBy { level[it.id]!! }.toSortedMap().values.map { col -> col.map { it.id } }
        return Layout(columns, edges(byId.keys))
    }

    private fun edges(shown: Set<String>) = nodes.filter { it.id in shown }.flatMap { n -> n.binding.dependsOn.filter { it in shown }.map { n.id to it } }
}
