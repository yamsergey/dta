package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.Alignment
import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.layout.Window
import kotlin.math.abs

/**
 * Feature 014 (research R4): how the tree's bounds land on the image. Each app window on screen
 * is paired with a tree window (same size first, then by order: the tree is bottom first, the
 * screen top first), and the pairs must agree on one offset or one uniform scale.
 */
object ScreenAlignment {
    data class Result(val alignment: Alignment, val windows: List<ScreenWindow>)

    fun align(tree: List<Window>, screen: List<ScreenWindow>, absolute: Boolean = false): Result {
        if (absolute) return Result(Alignment(AlignmentKind.ALIGNED), screen)
        val free = tree.indices.reversed().toMutableList()
        val pairs = mutableMapOf<Int, Int>()
        val appIndices = screen.indices.filter { screen[it].app }
        for (s in appIndices) {
            val b = screen[s].bounds
            free.firstOrNull { t -> tree[t].root.bounds.let { it.width == b.width && it.height == b.height } }
                ?.let { pairs[s] = it; free.remove(it) }
        }
        for (s in appIndices) if (s !in pairs && free.isNotEmpty()) pairs[s] = free.removeAt(0)
        val windows = screen.mapIndexed { i, w -> pairs[i]?.let { w.copy(treeWindow = it) } ?: w }
        if (pairs.isEmpty()) {
            return Result(Alignment(AlignmentKind.NOT_ALIGNED, reason = "none of the app's windows in the tree is on screen"), windows)
        }
        val fits = pairs.map { (s, t) -> fit(tree[t].root.bounds, screen[s].bounds) }
        val first = fits.first()
        val agree = fits.all { it != null && it == first }
        val alignment = when {
            first == null || !agree -> Alignment(
                AlignmentKind.NOT_ALIGNED,
                reason = "the tree's windows and the windows on screen do not match: " +
                    pairs.entries.joinToString("; ") { (s, t) -> "${tree[t].root.bounds.show()} vs ${screen[s].bounds.show()}" },
            )
            first.scale != 1f -> Alignment(AlignmentKind.SCALED, scale = first.scale)
            first.dx != 0 || first.dy != 0 -> Alignment(AlignmentKind.OFFSET, dx = first.dx, dy = first.dy)
            else -> Alignment(AlignmentKind.ALIGNED)
        }
        return Result(alignment, windows)
    }

    private data class Fit(val dx: Int, val dy: Int, val scale: Float)

    /** The offset (at scale 1) or the uniform scale (at offset 0) that maps [from] onto [to]. */
    private fun fit(from: Rect, to: Rect): Fit? {
        if (from.width == to.width && from.height == to.height) return Fit(to.left - from.left, to.top - from.top, 1f)
        if (from.width <= 0 || from.height <= 0) return null
        val sx = to.width.toFloat() / from.width
        val sy = to.height.toFloat() / from.height
        if (abs(sx - sy) > 0.01f) return null
        if (abs(from.left * sx - to.left) > 1 || abs(from.top * sy - to.top) > 1) return null
        return Fit(0, 0, sx)
    }

    private fun Rect.show() = "[$left,$top][$right,$bottom]"
}
