package io.yamsergey.dta.present.layout

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.network.arr
import io.yamsergey.dta.present.network.bool
import io.yamsergey.dta.present.network.long
import io.yamsergey.dta.present.network.obj
import io.yamsergey.dta.present.network.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A node's kind tag and the design token it is drawn in. */
enum class NodeKind(val tag: String, val token: String) {
    VIEW("view", "node-view"),
    COMPOSE("compose", "node-compose"),
    FALLBACK("fallback", "caveat"),
    ;

    companion object {
        fun of(kind: String?): NodeKind = entries.firstOrNull { it.tag == kind } ?: FALLBACK
    }
}

/**
 * One row of the tree (information.md): kind tag, name, identifiers, quoted text, interactions,
 * source line, and the node's own degradation statements.
 */
data class NodeRow(
    val kind: NodeKind,
    val name: String,
    val ids: List<String>,
    val text: String?,
    val interactions: List<String>,
    val source: String?,
    val disabled: Boolean,
    val statements: List<Statement>,
) {
    /** The row as one plain line, the way `dta layout` prints it. */
    val line: String
        get() = listOfNotNull(
            kind.tag, name, ids.joinToString(" ").ifEmpty { null }, text,
            interactions.joinToString(",").ifEmpty { null }, source, "disabled".takeIf { disabled },
        ).joinToString(" ")

    companion object {
        fun of(n: JsonObject): NodeRow {
            val kind = NodeKind.of(n.str("kind"))
            return NodeRow(
                kind = kind,
                name = name(n),
                ids = listOfNotNull(n.str("resourceId"), n.str("testTag")?.let { "testTag=$it" }, n.long("id")?.let { "#$it" }),
                text = (n.str("text") ?: n.str("contentDescription"))?.let { "\"${it.take(TEXT_PREVIEW)}\"" },
                interactions = n.arr("interactions").map { (it as JsonPrimitive).content },
                source = n.str("sourceFile")?.let { "$it:${n.long("lineNumber") ?: "?"}" },
                disabled = (n["enabled"] as? JsonPrimitive)?.content == "false",
                statements = Statements.markers(n["degraded"]),
            )
        }

        fun name(n: JsonObject): String = when (n.str("kind")) {
            "compose" -> n.str("name") ?: n.str("role")?.let { "role=$it" } ?: "Composable"
            else -> n.str("className")?.substringAfterLast('.') ?: "?"
        }

        private const val TEXT_PREVIEW = 60
    }
}

/** A node in place: [path] is the window index, then the child index at each level. */
data class TreeNode(val path: List<Int>, val row: NodeRow, val json: JsonObject, val children: List<TreeNode>)

data class WindowRow(val index: Int, val label: String, val root: TreeNode)

/**
 * A `layout.tree` result as the Layout view shows it (FR-011): a header line, the capture's
 * statements (fallback, degradation, reduced detail) for banners, and the windows' trees.
 */
data class LayoutTree(
    val summary: String,
    val statements: List<Statement>,
    val windows: List<WindowRow>,
    /** Feature 014: the picture and the windows on screen, when the capture took them. */
    val screen: ScreenInfo? = null,
) {

    /** By path in the capture (also in a [visible] tree, whose nodes keep their capture paths). */
    private val byPath: Map<List<Int>, TreeNode> by lazy { all().associateBy { it.path } }

    fun node(path: List<Int>): TreeNode? = byPath[path]

    /** Layout-only containers (Compose nodes known from tooling only) hidden from this tree. */
    val hiddenContainers: Int get() = hidden

    /**
     * Feature 057: the tree as shown with layout-only containers hidden ([hideContainers]) or not:
     * a hidden container's children take its place; every node keeps its capture path, so
     * selection, search, hover and the screen work the same on both trees.
     */
    fun visible(hideContainers: Boolean): LayoutTree {
        if (!hideContainers) return this
        var count = 0
        fun lift(n: TreeNode): List<TreeNode> {
            val kids = n.children.flatMap(::lift)
            return if (isContainer(n)) { count++; kids } else listOf(n.copy(children = kids))
        }
        val shown = windows.map { w -> w.copy(root = w.root.copy(children = w.root.children.flatMap(::lift))) }
        return copy(windows = shown).also { it.hidden = count }
    }

    private var hidden = 0

    /** Every node, depth first in tree order. */
    fun all(): Sequence<TreeNode> = sequence {
        suspend fun SequenceScope<TreeNode>.walk(n: TreeNode) {
            yield(n)
            n.children.forEach { walk(it) }
        }
        windows.forEach { walk(it.root) }
    }

    /** Each window's root and the level below it are expanded: the root plus two levels show. */
    val initiallyExpanded: Set<List<Int>>
        get() = all().filter { it.path.size <= 2 && it.children.isNotEmpty() }.map { it.path }.toSet()

    companion object {
        /** A layout-only container: a Compose node the capture knows from tooling data only. */
        fun isContainer(n: TreeNode): Boolean = n.json.str("kind") == "compose" && n.json.str("provenance") == "tooling"

        fun of(capture: JsonObject): LayoutTree {
            val screen = capture.obj("screen")
            val summary = listOfNotNull(
                screen?.let { "${it.long("widthPx")}×${it.long("heightPx")} px" },
                screen?.str("activity")?.substringAfterLast('.'),
                capture.str("source"),
                capture.obj("detail")?.str("delivered")?.let { "detail $it" },
            ).joinToString(" · ")
            val windows = capture.arr("windows").mapIndexed { i, w ->
                val window = w as JsonObject
                WindowRow(i, "window ${window.str("type")}" + (window.str("title")?.let { " \"$it\"" } ?: ""), build(window.obj("root")!!, listOf(i)))
            }
            return LayoutTree(summary, Statements.forCapture(capture), windows, ScreenInfo.of(capture))
        }

        private fun build(n: JsonObject, path: List<Int>): TreeNode =
            TreeNode(path, NodeRow.of(n), n, n.arr("children").mapIndexed { i, c -> build(c as JsonObject, path + i) })
    }
}

/**
 * A node's (or call's) source line; the IDE resolves [file] by [packageName] when known
 * (feature 054). [library]: library code; [unknown]: neither app nor library could be told.
 */
data class Source(
    val file: String,
    val line: Int,
    val packageName: String? = null,
    val library: Boolean = false,
    val unknown: Boolean = false,
) {
    companion object {
        /** The source of a node or a call-chain frame, when it names a file and a line. */
        fun of(o: JsonObject): Source? = o.str("sourceFile")?.let { f ->
            o.long("lineNumber")?.let { Source(f, it.toInt(), o.str("packageName"), o.bool("library"), o.bool("unknownOrigin")) }
        }
    }
}

/** The detail pane of the selected node (information.md). */
data class NodeDetail(
    val title: String,
    val breadcrumb: List<String>,
    val fields: List<Pair<String, String>>,
    val source: Source?,
    val parameters: List<Parameter>,
    val callChain: List<Call>,
    val statements: List<Statement>,
    /** Feature 054: the app calls above this node (nearest first), offered when its source is not found. */
    val callers: List<Call> = emptyList(),
) {
    /** A call as shown ("Name (File.kt:12)") and its source when it has one. */
    data class Call(val text: String, val source: Source?)

    /** [note]: `cut` (the value was cut at its bound) or `default` (not passed by the caller), feature 018. */
    data class Parameter(val name: String, val value: String, val type: String, val note: String? = null)

    companion object {
        fun of(tree: LayoutTree, path: List<Int>): NodeDetail? {
            val node = tree.node(path) ?: return null
            val n = node.json
            val breadcrumb = (1..path.size).mapNotNull { tree.node(path.take(it))?.row?.name }
            val fields = buildList {
                add("Kind" to node.row.kind.tag)
                n.str("className")?.let { add("Class" to it) }
                n.str("name")?.let { add("Name" to it) }
                n.str("role")?.let { add("Role" to it) }
                n.obj("bounds")?.let { add("Bounds" to bounds(it)) }
                n.long("id")?.let { add("Id" to "#$it") }
                n.str("resourceId")?.let { add("Resource id" to it) }
                n.str("testTag")?.let { add("Test tag" to it) }
                n.str("text")?.let { add("Text" to "\"$it\"") }
                n.str("hint")?.let { add("Hint" to "\"$it\"") }
                n.str("contentDescription")?.let { add("Content description" to "\"$it\"") }
                node.row.interactions.ifEmpty { null }?.let { add("Interactions" to it.joinToString(", ")) }
                if (node.row.disabled) add("Enabled" to "false")
                n.str("visibility")?.takeIf { it != "visible" }?.let { add("Visibility" to it) }
                if ((n["visible"] as? JsonPrimitive)?.content == "false") add("Visible" to "false")
                n.str("checked")?.let { add("Checked" to it) }
                n.str("selected")?.let { add("Selected" to it) }
                n.str("provenance")?.let { add("Provenance" to it) }
                if (n.bool("library")) add("Library" to "true")
                n.str("packageName")?.let { add("Package" to it) }
                if (n.bool("unknownOrigin")) add("Origin" to "unknown (package not among the app's)")
                n.long("sameCallAs")?.let { add("Same call as" to "#$it (its parameters are there)") }
                n.long("defaultParameters")?.let { add("Defaults" to "$it parameters at their defaults, not listed") }
            }
            return NodeDetail(
                title = node.row.name,
                breadcrumb = breadcrumb,
                fields = fields,
                source = Source.of(n),
                parameters = n.arr("parameters").map { p ->
                    (p as JsonObject).let {
                        val note = when {
                            it.bool("cut") -> "cut"
                            it.bool("default") -> "default"
                            else -> null
                        }
                        Parameter(it.str("name") ?: "", it.str("value") ?: "", it.str("type") ?: "", note)
                    }
                },
                callChain = n.arr("callChain").map { f -> call(f as JsonObject) },
                statements = node.row.statements,
                callers = (path.size - 1 downTo 1).mapNotNull { tree.node(path.take(it))?.json }
                    .filter { a -> Source.of(a)?.let { !it.library && !it.unknown } == true }
                    .map(::call)
                    .filter { it.source != Source.of(n) }
                    .distinctBy { it.source }
                    .take(MAX_CALLERS),
            )
        }

        private const val MAX_CALLERS = 3

        private fun call(o: JsonObject) = Call(
            "${o.str("name")}" + (o.str("sourceFile")?.let { s -> " ($s:${o.long("lineNumber") ?: "?"})" } ?: ""),
            Source.of(o),
        )

        private fun bounds(b: JsonObject): String {
            val (l, t, r, bt) = listOf("left", "top", "right", "bottom").map { b.long(it) ?: 0 }
            return "$l,$t – $r,$bt (${r - l}×${bt - t})"
        }
    }
}
