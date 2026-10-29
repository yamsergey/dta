package io.yamsergey.dta.present.layout

import io.yamsergey.dta.present.network.str

/**
 * A search in the tree (FR-012, patterns.md tree): [matches] in tree order, [ancestors] to expand
 * so every match shows. With non-matching rows hidden, only matches and their ancestors remain,
 * the ancestors dimmed.
 */
data class SearchResult(val query: String, val matches: List<List<Int>>, val ancestors: Set<List<Int>>) {
    private val matched = matches.toSet()

    fun visible(path: List<Int>): Boolean = path in matched || path in ancestors

    fun dimmed(path: List<Int>): Boolean = path in ancestors && path !in matched

    fun next(current: List<Int>?): List<Int>? = step(current, +1)

    fun previous(current: List<Int>?): List<Int>? = step(current, -1)

    private fun step(current: List<Int>?, by: Int): List<Int>? {
        if (matches.isEmpty()) return null
        val i = matches.indexOf(current)
        if (i < 0) return if (by > 0) matches.first() else matches.last()
        return matches[(i + by).mod(matches.size)]
    }

    /** "2 of 3" on a match, else the count. */
    fun position(current: List<Int>?): String {
        val i = matches.indexOf(current)
        return when {
            matches.isEmpty() -> "no matches"
            i >= 0 -> "${i + 1} of ${matches.size}"
            else -> "${matches.size} match${if (matches.size == 1) "" else "es"}"
        }
    }
}

/** Where the selection is after a refresh, and why it is gone when it is. */
data class Reselection(val path: List<Int>?, val note: String?)

object TreeSearch {

    /** Matches the name, class, text, content description, resource id and test tag, ignoring case. */
    fun find(tree: LayoutTree, query: String): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(query, emptyList(), emptySet())
        val matches = tree.all().filter { node ->
            val n = node.json
            listOfNotNull(node.row.name, n.str("className"), n.str("text"), n.str("contentDescription"), n.str("resourceId"), n.str("testTag"))
                .any { it.contains(q, ignoreCase = true) }
        }.map { it.path }.toList()
        return SearchResult(query, matches, matches.flatMap { p -> (1 until p.size).map { p.take(it) } }.toSet())
    }

    /**
     * The selected node in a new capture: the node with the same kind and id (one, or the one at
     * the same path), else the node at the same path with the same kind and name, else none.
     */
    fun reselect(old: LayoutTree, selected: List<Int>, new: LayoutTree): Reselection {
        val was = old.node(selected) ?: return Reselection(null, null)
        val id = was.json["id"]?.let { it.toString() }
        if (id != null) {
            val same = new.all().filter { it.row.kind == was.row.kind && it.json["id"]?.toString() == id }.toList()
            (same.singleOrNull() ?: same.firstOrNull { it.path == selected })?.let { return Reselection(it.path, null) }
        }
        new.node(selected)?.takeIf { it.row.kind == was.row.kind && it.row.name == was.row.name }?.let { return Reselection(it.path, null) }
        val label = was.row.name + (was.json.str("id")?.let { " (#$it)" } ?: "")
        return Reselection(null, "The selected node $label is no longer on screen.")
    }
}
