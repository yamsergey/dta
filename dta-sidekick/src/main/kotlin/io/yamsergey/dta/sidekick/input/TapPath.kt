package io.yamsergey.dta.sidekick.input

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.layout.LayoutInfo
import java.lang.reflect.Proxy

/**
 * Feature 064 (research R1): the slot-table path to one layout node, and a pruned view of the
 * table that the tooling `mapTree` reads like the whole one. Only the path is mapped, so a tap's
 * chain costs about 6 ms instead of the whole host's 35.
 *
 * Group objects are created fresh on every iteration, so the path is kept as child indices,
 * never compared by reference. The view keeps each earlier sibling at every step as a stub: a
 * sibling advances its parent's call-site counter. A stub with its own source information keeps
 * no children; one without keeps its children by the same rule, because they consume the parent's
 * locations. The view is a `java.lang.reflect.Proxy`, so whichever interface methods a Compose
 * version has are forwarded to the real group.
 */
object TapPath {
    /** [steps] are child indices from the table's roots; [path] the groups along them. */
    class Found(val table: Int, val path: List<CompositionGroup>, val steps: List<Int>)

    /**
     * Where each of [nodes] sits, in one walk of all [tables] (a layout node belongs to exactly
     * one table). Stops as soon as all are found; a node in no table is absent from the result.
     */
    fun findAll(tables: List<CompositionData>, nodes: List<Any>): Map<Any, Found> {
        val wanted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()).apply { addAll(nodes) }
        val found = java.util.IdentityHashMap<Any, Found>()
        tables.forEachIndexed { i, table ->
            val path = ArrayList<CompositionGroup>()
            val steps = ArrayList<Int>()
            fun walk(g: CompositionGroup, at: Int): Boolean {
                path += g
                steps += at
                val n = g.node
                if (n != null && n in wanted && n !in found) {
                    found[n] = Found(i, ArrayList(path), ArrayList(steps))
                    if (found.size == wanted.size) return true
                }
                var k = 0
                for (c in g.compositionGroups) if (walk(c, k++)) return true
                path.removeAt(path.size - 1)
                steps.removeAt(steps.size - 1)
                return false
            }
            var r = 0
            for (root in table.compositionGroups) if (walk(root, r++)) return found
        }
        return found
    }

    /** Where [node] sits; null when in no table. */
    fun find(tables: List<CompositionData>, node: Any): Found? = findAll(tables, listOf(node))[node]

    /** The pruned view of [table] along [steps]. */
    fun pruned(table: CompositionData, steps: List<Int>): CompositionData {
        fun level(children: Iterable<CompositionGroup>, depth: Int): List<CompositionGroup> {
            val out = ArrayList<CompositionGroup>()
            var k = 0
            for (c in children) {
                if (k++ == steps[depth]) {
                    out += wrap(c, if (depth + 1 < steps.size) level(c.compositionGroups, depth + 1) else emptyList())
                    break
                }
                out += stub(c)
            }
            return out
        }
        return proxy(CompositionData::class.java, null, level(table.compositionGroups, 0))
    }

    /** Layout ancestors of [node], nearest first. */
    fun ancestors(node: LayoutInfo): Sequence<LayoutInfo> = generateSequence(node.parentInfo) { it.parentInfo }

    private fun stub(g: CompositionGroup): CompositionGroup =
        wrap(g, if (g.sourceInfo != null) emptyList() else g.compositionGroups.map(::stub))

    private fun wrap(g: CompositionGroup, children: List<CompositionGroup>): CompositionGroup =
        proxy(CompositionGroup::class.java, g, children)

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, real: CompositionGroup?, children: List<CompositionGroup>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, m, args ->
            when (m.name) {
                "getCompositionGroups" -> children
                "isEmpty" -> children.isEmpty()
                "find" -> null
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "TapPath(${real?.sourceInfo})"
                else -> if (real == null) null else m.invoke(real, *(args ?: emptyArray()))
            }
        } as T
}
