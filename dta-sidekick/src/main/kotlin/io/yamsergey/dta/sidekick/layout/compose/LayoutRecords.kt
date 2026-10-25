package io.yamsergey.dta.sidekick.layout.compose

import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import io.yamsergey.dta.protocol.layout.Rect

/**
 * Device adapter for the `layout` level: turns the layout nodes found through
 * tooling into [LayoutRecord]s. Parents come from LayoutInfo.parentInfo; any
 * ancestor tooling did not report is recorded too, so every chain reaches the
 * host root (no orphaned containers). Bounds use the same window space and
 * clipping as SemanticsNode.boundsInWindow; a node that clips to nothing
 * reports its unclipped position and size instead.
 */
object LayoutRecords {

    fun from(layoutNodes: Map<Long, LayoutInfo>): List<LayoutRecord> {
        val out = LinkedHashMap<Long, LayoutRecord>()
        val pending = ArrayDeque(layoutNodes.values)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val id = LayoutNodeProbes.semanticsId(node) ?: continue
            if (id in out) continue
            val parent = node.parentInfo
            val parentId = parent?.let { LayoutNodeProbes.semanticsId(it) }
            val (bounds, onScreen) = boundsOf(node)
            out[id] = LayoutRecord(
                id = id,
                parentId = parentId,
                bounds = bounds,
                placed = node.isPlaced,
                interopViewId = LayoutNodeProbes.interopViewDrawingId(node),
                onScreen = onScreen,
            )
            if (parent != null && parentId != null && parentId !in out) pending.addLast(parent)
        }
        return out.values.toList()
    }

    /** Window bounds, and whether anything of the node survives clipping. */
    private fun boundsOf(node: LayoutInfo): Pair<Rect, Boolean> {
        val coordinates = node.coordinates
        if (!node.isAttached || !coordinates.isAttached) return Rect(0, 0, 0, 0) to false
        val b = coordinates.boundsInWindow()
        if (b.width > 0f && b.height > 0f) return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()) to true
        // Clipped to nothing (zero-area or off-screen): report where it is.
        val p = coordinates.positionInWindow()
        val size = coordinates.size
        val unclipped = Rect(p.x.toInt(), p.y.toInt(), p.x.toInt() + size.width, p.y.toInt() + size.height)
        return unclipped to (unclipped.area == 0)
    }
}
