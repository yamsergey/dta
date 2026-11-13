package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.LayoutSelection
import io.yamsergey.dta.protocol.layout.SelectedElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 055: the Layout tree's selected rows as shared elements, and a shared selection back
 * as rows of the tree shown (by id, kind and name; a node without an id by its path and bounds).
 */
object SelectionMap {
    /** The rows at [paths] of the whole capture [whole], as the developer saw them. */
    fun elements(whole: LayoutTree, paths: List<List<Int>>, capturedAt: Long, source: String? = null): List<SelectedElement> =
        paths.mapNotNull { p ->
            val node = whole.node(p) ?: return@mapNotNull null
            SelectedElement(JsonObject(node.json - "children"), names(whole, p), capturedAt, screen(whole), source)
        }

    /** The rows of [whole] holding [selection]'s elements, in its order, and how many it lacks. */
    fun paths(whole: LayoutTree, selection: LayoutSelection): Pair<List<List<Int>>, Int> {
        val all = whole.all().toList()
        val found = selection.elements.map { e ->
            val id = e.node.str("id")
            all.firstOrNull { n ->
                if (id != null) n.json.str("id") == id && n.json.str("kind") == e.node.str("kind") && name(n.json) == name(e.node)
                else names(whole, n.path) == e.path && n.json["bounds"] == e.node["bounds"]
            }?.path
        }
        return found.filterNotNull() to found.count { it == null }
    }

    /** Names from the window root to [path]: "DecorView › Scaffold › Button". */
    fun names(whole: LayoutTree, path: List<Int>): List<String> = (1..path.size).mapNotNull { whole.node(path.take(it))?.row?.name }

    /** "1080×2400 · MainActivity" from the tree's summary. */
    private fun screen(whole: LayoutTree): String? =
        whole.summary.split(" · ").take(2).joinToString(" · ") { it.removeSuffix(" px") }.ifEmpty { null }

    private fun name(n: JsonObject) = n.str("name") ?: n.str("className")

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
}
