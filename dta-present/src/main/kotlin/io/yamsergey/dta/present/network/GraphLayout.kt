package io.yamsergey.dta.present.network

/** Sizes of the graph's cards, in pixels at zoom 1 (feature 022). */
data class GraphStyle(
    val titleHeight: Int = 24,
    val rowHeight: Int = 20,
    val padding: Int = 8,
    val gapX: Int = 56,
    val gapY: Int = 16,
    val minWidth: Int = 96,
    val maxWidth: Int = 360,
    /** Rows a card shows before "+N more". */
    val maxRows: Int = 20,
)

/** One row of a card: a member and its value, or the summary of a nested object/array/element. */
data class GraphRow(
    val key: String,
    val text: String,
    val kind: NodeKind,
    /** The member's path; for "+N more", the card's. */
    val path: String,
    val container: Boolean,
    val expanded: Boolean,
    /** Rows not shown, on the "+N more" row; 0 otherwise. */
    val more: Int = 0,
)

data class GraphCard(
    val path: String,
    val title: String,
    val kind: NodeKind,
    val depth: Int,
    val rows: List<GraphRow>,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** From row [row] of the card at [from] to the card at [to]. */
data class GraphEdge(val from: String, val row: Int, val to: String)

data class DataGraph(val cards: List<GraphCard>, val edges: List<GraphEdge>, val width: Int, val height: Int) {
    private val byPath = cards.associateBy { it.path }
    fun card(path: String): GraphCard? = byPath[path]
}

/** Which nested values have their own card; [truncated] when a card limit stopped the expansion. */
data class Expansion(val paths: Set<String>, val truncated: Boolean)

/**
 * The body as a graph of cards, left to right (feature 022, research R3): one card per shown
 * object, array or element; a parent is centred on its children; a column is as wide as its
 * widest card. Text widths come from [layout]'s `measure`, so the plugin lays out with its font.
 */
object GraphLayout {
    /** Marks a card showing all its rows: `path + MORE`. */
    const val MORE = "#all"
    const val MAX_CARDS = 200

    /** Cards open when a body is first shown: enough to read, few enough to see at full size. */
    const val DEFAULT_CARDS = 40

    /** Items of an array opened at first; the rest are rows to click. */
    const val ARRAY_ITEMS = 3

    fun moreKey(path: String) = path + MORE

    fun layout(root: DataNode, expanded: Set<String>, measure: (String) -> Int, style: GraphStyle = GraphStyle()): DataGraph {
        val cards = ArrayList<GraphCard>()
        val edges = ArrayList<GraphEdge>()
        val columnWidth = HashMap<Int, Int>()

        // First pass: what is shown, each card's rows and size.
        class Pending(val node: DataNode, val depth: Int, val rows: List<GraphRow>, val width: Int, val height: Int, val kids: List<Pending>)

        fun build(node: DataNode, depth: Int): Pending {
            val all = node.path + MORE in expanded
            val members = if (all) node.children else node.children.take(style.maxRows)
            val rows = members.map { child ->
                GraphRow(child.key, child.summary, child.kind, child.path, child.container, child.container && child.path in expanded)
            }.toMutableList()
            val hidden = node.children.size - members.size
            if (hidden > 0) rows += GraphRow("", "+$hidden more", node.kind, node.path, container = false, expanded = false, more = hidden)
            val text = (rows.map { if (it.key.isEmpty()) it.text else "${it.key}: ${it.text}" } + node.title).maxOf(measure)
            val width = (text + style.padding * 2).coerceIn(style.minWidth, style.maxWidth)
            val height = style.titleHeight + rows.size * style.rowHeight + style.padding
            columnWidth.merge(depth, width, ::maxOf)
            val kids = members.filter { it.container && it.path in expanded }.map { build(it, depth + 1) }
            return Pending(node, depth, rows, width, height, kids)
        }
        val tree = build(root, 0)

        val columnX = HashMap<Int, Int>()
        var x = 0
        for (d in 0..(columnWidth.keys.maxOrNull() ?: 0)) {
            columnX[d] = x
            x += (columnWidth[d] ?: 0) + style.gapX
        }

        // Second pass: y, children first; the parent centred on them, never above the free line.
        fun place(p: Pending, top: Int): Int {
            var y = top
            val placed = p.kids.map { kid -> y = place(kid, y); cards.last() }
            val cardY = if (placed.isEmpty()) top else maxOf(top, (placed.first().y + placed.last().let { it.y + it.height }) / 2 - p.height / 2)
            // Added after its children: the card just placed is always the last one.
            cards += GraphCard(p.node.path, p.node.title, p.node.kind, p.depth, p.rows, columnX.getValue(p.depth), cardY, p.width, p.height)
            p.kids.forEach { kid -> edges += GraphEdge(p.node.path, p.rows.indexOfFirst { it.path == kid.node.path }, kid.node.path) }
            return maxOf(y, cardY + p.height + style.gapY)
        }
        val bottom = place(tree, 0)

        // Cards in reading order: by depth, then top to bottom; edges in row order.
        val ordered = cards.sortedWith(compareBy({ it.depth }, { it.y }))
        val rank = ordered.withIndex().associate { (i, c) -> c.path to i }
        val width = ordered.maxOf { it.x + it.width }
        return DataGraph(ordered, edges.sortedWith(compareBy({ rank.getValue(it.from) }, { it.row })), width, bottom - style.gapY)
    }

    /**
     * Two levels under the root expanded, breadth first: an array's first [ARRAY_ITEMS] items
     * only, at most [maxCards] cards with the root.
     */
    fun defaultExpanded(root: DataNode, depth: Int = 2, maxCards: Int = DEFAULT_CARDS, maxRows: Int = GraphStyle().maxRows): Set<String> =
        expand(root, depth, maxCards, maxRows, all = false).paths

    /** Everything expanded, all rows shown, until [maxCards] cards. */
    fun expandAll(root: DataNode, maxCards: Int = MAX_CARDS): Expansion = expand(root, Int.MAX_VALUE, maxCards, Int.MAX_VALUE, all = true)

    private fun expand(root: DataNode, depth: Int, maxCards: Int, maxRows: Int, all: Boolean): Expansion {
        val paths = LinkedHashSet<String>()
        var cards = 1
        val queue = ArrayDeque(listOf(root to 0))
        while (queue.isNotEmpty()) {
            val (node, d) = queue.removeFirst()
            if (all && node.children.size > GraphStyle().maxRows) paths += moreKey(node.path)
            if (d >= depth) continue
            val members = if (!all && node.kind == NodeKind.ARRAY) node.children.take(minOf(maxRows, ARRAY_ITEMS)) else node.children.take(maxRows)
            for (child in members) {
                if (!child.container) continue
                if (cards >= maxCards) return Expansion(paths, truncated = true)
                paths += child.path
                cards++
                queue += child to d + 1
            }
        }
        return Expansion(paths, truncated = false)
    }

    /** Expands [path], or collapses it with everything under it. */
    fun toggle(expanded: Set<String>, path: String): Set<String> =
        if (path in expanded) expanded.filterNot { it == path || isUnder(it, path) }.toSet() else expanded + path

    private fun isUnder(candidate: String, path: String): Boolean =
        candidate.length > path.length && candidate.startsWith(path) && candidate[path.length] in "./[#"
}
