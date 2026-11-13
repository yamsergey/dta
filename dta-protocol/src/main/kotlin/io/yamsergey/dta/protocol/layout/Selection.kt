package io.yamsergey.dta.protocol.layout

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 055: one element of the shared layout selection, described as it was when selected:
 * the node (without its children), the names from the window root to it, the capture it came
 * from, and whether it is still on the latest captured screen ([now]: on-screen, gone, not-checked).
 */
@Serializable
data class SelectedElement(
    val node: JsonObject,
    val path: List<String> = emptyList(),
    val capturedAt: Long,
    val screen: String? = null,
    val source: String? = null,
    val now: String? = null,
)

/** Feature 055: an app's layout selection, shared by the plugin, the CLI and agents. */
@Serializable
data class LayoutSelection(
    val device: String,
    val app: String,
    val revision: Long = 0,
    val elements: List<SelectedElement> = emptyList(),
    val changedBy: String? = null,
    val changedAt: Long? = null,
    /** Elements beyond [Selections.MAX] that were left out. */
    val omitted: Int? = null,
    /** Why the selection is as it is, e.g. cleared when the app process ended. */
    val note: String? = null,
)

/** Feature 055: the `layout.selection` event: the selection after a change, and who made it. */
@Serializable
data class SelectionEvent(val selection: LayoutSelection, val by: String? = null)

/** Feature 055: pure rules of the shared selection. */
object Selections {
    const val MAX = 50
    const val ON_SCREEN = "on-screen"
    const val GONE = "gone"
    const val NOT_CHECKED = "not-checked"
    const val ENDED = "cleared: the app process ended"

    /** A node as an element: children left out; [path] the names from the window root. */
    fun element(node: Node, path: List<String>, capturedAt: Long, screen: Screen?, source: CaptureSource?): SelectedElement {
        val json = DtaJson.encodeToJsonElement(Node.serializer(), node) as JsonObject
        return SelectedElement(JsonObject(json - "children"), path, capturedAt, screen?.let(::screenLabel), source?.let { DtaJson.encodeToJsonElement(CaptureSource.serializer(), it).let { (it as JsonPrimitive).content } })
    }

    fun screenLabel(s: Screen): String = "${s.widthPx}×${s.heightPx}" + (s.activity?.let { " · ${it.substringAfterLast('.')}" } ?: "")

    /** A node's short name: a composable's name, a View's simple class name. */
    fun name(node: Node): String = when (node) {
        is ViewNode -> node.className.substringAfterLast('.')
        is ComposeNode -> node.name ?: "compose"
        is FallbackNode -> node.className.substringAfterLast('.')
    }

    /** Every node of [capture] with its path, windows top first. */
    fun walk(capture: LayoutCapture): Sequence<Pair<Node, List<String>>> = sequence {
        for (w in capture.windows.sortedByDescending { it.zOrder }) {
            suspend fun SequenceScope<Pair<Node, List<String>>>.visit(n: Node, path: List<String>) {
                val here = path + name(n)
                yield(n to here)
                n.children.forEach { visit(it, here) }
            }
            visit(w.root, emptyList())
        }
    }

    /** Nodes of [capture] by id, in the order asked; the ids it lacks in [missing]. */
    fun byIds(capture: LayoutCapture, ids: List<Long>): Pair<List<Pair<Node, List<String>>>, List<Long>> {
        val all = walk(capture).filter { it.first.id != null }.associateBy { it.first.id!! }
        return ids.mapNotNull { all[it] } to ids.filter { it !in all }
    }

    /** The innermost node of the topmost window holding the point, or null. */
    fun at(capture: LayoutCapture, x: Int, y: Int): Pair<Node, List<String>>? {
        for (w in capture.windows.sortedByDescending { it.zOrder }) {
            fun inside(n: Node) = n.bounds.let { x >= it.left && x < it.right && y >= it.top && y < it.bottom && it.right > it.left && it.bottom > it.top }
            if (!inside(w.root)) continue
            var node = w.root
            val path = mutableListOf(name(node))
            while (true) {
                val next = node.children.lastOrNull { inside(it) } ?: break
                node = next
                path += name(node)
            }
            return node to path
        }
        return null
    }

    /** "x,y" → the point, or null. */
    fun point(at: String): Pair<Int, Int>? = at.split(',').takeIf { it.size == 2 }?.let { (a, b) -> a.trim().toIntOrNull()?.let { x -> b.trim().toIntOrNull()?.let { x to it } } }

    /**
     * Is [e] on the latest screen: the same id, kind and name in the latest capture taken at or after it (on-screen),
     * missing from it (gone), or none since (not-checked). An element without an id is not-checked.
     */
    fun freshness(e: SelectedElement, latest: LayoutCapture?, latestAt: Long?): String {
        if (latest == null || latestAt == null || latestAt < e.capturedAt) return NOT_CHECKED
        val id = (e.node["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return NOT_CHECKED
        val kind = (e.node["kind"] as? JsonPrimitive)?.content
        val name = ((e.node["name"] ?: e.node["className"]) as? JsonPrimitive)?.content
        val found = walk(latest).any { (n, _) ->
            n.id == id && when (n) {
                is ViewNode -> kind == "view" && name == n.className
                is ComposeNode -> kind == "compose" && name == n.name
                is FallbackNode -> kind == "fallback" && name == n.className
            }
        }
        return if (found) ON_SCREEN else GONE
    }

    /** The same element (for add / remove): the same id and kind, or for nodes without an id the same path and bounds. */
    fun same(a: SelectedElement, b: SelectedElement): Boolean {
        val ia = a.node["id"]; val ib = b.node["id"]
        return if (ia != null && ib != null) ia == ib && a.node["kind"] == b.node["kind"] else a.path == b.path && a.node["bounds"] == b.node["bounds"]
    }

    /** [current] changed by [mode] with [elements]; bounded to [MAX]. */
    fun apply(current: LayoutSelection, mode: String, elements: List<SelectedElement>, by: String, at: Long): LayoutSelection {
        val next = when (mode) {
            "set" -> elements
            "add" -> current.elements + elements.filter { e -> current.elements.none { same(it, e) } }
            "remove" -> current.elements.filter { e -> elements.none { same(it, e) } }
            "clear" -> emptyList()
            else -> throw IllegalArgumentException("mode takes set, add, remove or clear; was '$mode'")
        }
        val distinct = next.fold(emptyList<SelectedElement>()) { acc, e -> if (acc.any { same(it, e) }) acc else acc + e }
        return current.copy(
            revision = current.revision + 1,
            elements = distinct.take(MAX).map { it.copy(now = null) },
            omitted = (distinct.size - MAX).takeIf { it > 0 },
            changedBy = by,
            changedAt = at,
            note = null,
        )
    }
}
