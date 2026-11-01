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

    /** The app's nodes under the point, deepest first, up to the window root. */
    data class Nodes(val paths: List<List<Int>>) : ScreenHit {
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
            for (w in tree.windows.reversed()) deepest(w.root, tx, ty)?.let { return ScreenHit.Nodes(chain(it)) }
            return ScreenHit.Outside
        }
        val window = windows.firstOrNull { it.bounds.contains(x, y) } ?: return ScreenHit.Outside
        if (!window.app) return ScreenHit.Foreign(window)
        val roots = window.treeWindow?.let { listOfNotNull(tree.windows.getOrNull(it)) } ?: tree.windows.reversed()
        for (w in roots) deepest(w.root, tx, ty)?.let { return ScreenHit.Nodes(chain(it)) }
        return ScreenHit.Outside
    }

    private fun chain(path: List<Int>): List<List<Int>> = (path.size downTo 1).map { path.take(it) }

    private fun deepest(node: TreeNode, x: Double, y: Double): List<Int>? {
        if (node.json.str("visibility") == "invisible") return null
        for (child in node.children.reversed()) deepest(child, x, y)?.let { return it }
        val bounds = node.json["bounds"]?.let { DtaJson.decodeFromJsonElement(Rect.serializer(), it) } ?: return null
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val quad = node.json["renderOutline"]?.let { DtaJson.decodeFromJsonElement(Quad.serializer(), it) }
        val inside = quad?.contains(x, y) ?: bounds.contains(x, y)
        return if (inside) node.path else null
    }

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
