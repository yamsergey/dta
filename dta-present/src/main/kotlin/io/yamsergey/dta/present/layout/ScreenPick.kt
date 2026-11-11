package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.Alignment
import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.Quad
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ScreenImage
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** Feature 014: the capture's screen image and the windows on screen. */
class ScreenInfo(val image: ScreenImage, val windows: List<ScreenWindow>) {
    /** The PNG, when it came inline. */
    val png: ByteArray? by lazy { image.data?.let { Base64.getDecoder().decode(it) } }
    val alignment: Alignment get() = image.alignment ?: Alignment(AlignmentKind.NOT_ALIGNED, reason = "the capture did not say how the tree lines up")

    companion object {
        fun of(capture: JsonObject): ScreenInfo? {
            val image = capture["screenImage"] as? JsonObject ?: return null
            val windows = capture["screenWindows"]?.let { DtaJson.decodeFromJsonElement(ListSerializer(ScreenWindow.serializer()), it) }.orEmpty()
            return ScreenInfo(DtaJson.decodeFromJsonElement(ScreenImage.serializer(), image), windows)
        }
    }
}

/** What is under a point of the picture. */
sealed interface ScreenHit {
    val statement: String?

    /**
     * The app's nodes under the point: the one that answers first, then every other node there in
     * drawing order (top first), for "next under pointer". [skipped]: the empty full-window layer
     * the answer was found under, when there was one (feature 057).
     */
    data class Nodes(val paths: List<List<Int>>, val skipped: List<Int>? = null) : ScreenHit {
        override val statement: String? get() = null
    }

    data class Foreign(val window: ScreenWindow) : ScreenHit {
        override val statement: String
            get() = "not the app: ${DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.layout.ScreenWindowKind.serializer(), window.kind).let { (it as JsonPrimitive).content }} ${window.name}"
    }

    data object Outside : ScreenHit {
        override val statement: String get() = "outside the app's windows"
    }
}

/**
 * Feature 014 (research R7): the windows on screen top first; a window that is not the app's
 * answers for its area; in an app window, the deepest visible node containing the point (the
 * sibling drawn last wins), then its ancestors. Points are image pixels.
 */
object ScreenPick {
    fun at(tree: LayoutTree, x: Double, y: Double): ScreenHit {
        val screen = tree.screen
        val alignment = screen?.alignment ?: Alignment(AlignmentKind.ALIGNED)
        val (tx, ty) = x / alignment.scale - alignment.dx to y / alignment.scale - alignment.dy
        val windows = screen?.windows.orEmpty().sortedBy { it.zOrder }
        if (windows.isEmpty()) {
            for (w in tree.windows.reversed()) hit(tree, w.root, tx, ty)?.let { return it }
            return ScreenHit.Outside
        }
        val window = windows.firstOrNull { it.bounds.contains(x, y) } ?: return ScreenHit.Outside
        if (!window.app) return ScreenHit.Foreign(window)
        val roots = window.treeWindow?.let { listOfNotNull(tree.windows.getOrNull(it)) } ?: tree.windows.reversed()
        for (w in roots) hit(tree, w.root, tx, ty)?.let { return it }
        return ScreenHit.Outside
    }

    /**
     * Feature 057 (research R5): every node containing the point, top first (a later sibling
     * before an earlier one, children before their parent). The answer is the first that is not an
     * empty layer: a node with nothing of its own (no text, description, role or interaction) that
     * covers at least [LAYER_SHARE] of its parent and is drawn over another node holding the point
     * (one that is not its ancestor) — a sheet host or scrim kept over the screen would otherwise answer everywhere. All
     * layers: the first answers.
     */
    private fun hit(tree: LayoutTree, root: TreeNode, x: Double, y: Double): ScreenHit.Nodes? {
        val under = ArrayList<TreeNode>()
        collect(root, x, y, under)
        if (under.isEmpty()) return null
        fun layer(n: TreeNode): Boolean {
            if (hasContent(n) || n.path.size < 2) return false
            // The nearest ancestor in this tree (a hidden container is not one).
            val parent = (n.path.size - 1 downTo 1).firstNotNullOfOrNull { tree.node(n.path.take(it)) } ?: return false
            if (area(parent) <= 0 || area(n) < LAYER_SHARE * area(parent)) return false
            // Drawn over something else at the point: a node below it in drawing order that is not one of
            // its ancestors (an earlier sibling's subtree at any level — a scrim can be the only child of
            // wrappers whose outermost one lies over the screen).
            fun ancestorOfN(o: TreeNode) = o.path.size < n.path.size && n.path.subList(0, o.path.size) == o.path
            return under.dropWhile { it !== n }.drop(1).any { !ancestorOfN(it) }
        }
        val answer = under.firstOrNull { !layer(it) } ?: under.first()
        val skipped = under.takeWhile { it !== answer }.firstOrNull { layer(it) }?.path
        return ScreenHit.Nodes(listOf(answer.path) + under.filter { it !== answer }.map { it.path }, skipped)
    }

    private fun collect(node: TreeNode, x: Double, y: Double, out: MutableList<TreeNode>) {
        if (node.json.str("visibility") == "invisible") return
        for (child in node.children.reversed()) collect(child, x, y, out)
        val bounds = bounds(node) ?: return
        if (bounds.width <= 0 || bounds.height <= 0) return
        val quad = node.json["renderOutline"]?.let { DtaJson.decodeFromJsonElement(Quad.serializer(), it) }
        if (quad?.contains(x, y) ?: bounds.contains(x, y)) out += node
    }

    private fun bounds(n: TreeNode) = n.json["bounds"]?.let { DtaJson.decodeFromJsonElement(Rect.serializer(), it) }

    private fun area(n: TreeNode): Double = bounds(n)?.let { it.width.toDouble() * it.height } ?: 0.0

    private fun hasContent(n: TreeNode): Boolean =
        n.json.str("text") != null || n.json.str("contentDescription") != null || n.json.str("role") != null ||
            (n.json["interactions"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true

    private const val LAYER_SHARE = 0.95

    private fun Rect.contains(x: Double, y: Double) = x >= left && x < right && y >= top && y < bottom

    /** Point in a convex quad: the same side of all four edges. */
    private fun Quad.contains(x: Double, y: Double): Boolean {
        val xs = doubleArrayOf(x0.toDouble(), x1.toDouble(), x2.toDouble(), x3.toDouble())
        val ys = doubleArrayOf(y0.toDouble(), y1.toDouble(), y2.toDouble(), y3.toDouble())
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val cross = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.content
}
