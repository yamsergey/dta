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
            out[id] = LayoutRecord(
                id = id,
                parentId = parentId,
                bounds = boundsOf(node),
                placed = node.isPlaced,
                interopViewId = LayoutNodeProbes.interopViewDrawingId(node),
            )
            if (parent != null && parentId != null && parentId !in out) pending.addLast(parent)
        }
        return out.values.toList()
    }

    private fun boundsOf(node: LayoutInfo): Rect {
        val coordinates = node.coordinates
        if (!node.isAttached || !coordinates.isAttached) return Rect(0, 0, 0, 0)
        val b = coordinates.boundsInWindow()
        if (b.width > 0f && b.height > 0f) return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        // Clipping collapses zero-area nodes to (0,0); report where they are.
        val p = coordinates.positionInWindow()
        val size = coordinates.size
        return Rect(p.x.toInt(), p.y.toInt(), p.x.toInt() + size.width, p.y.toInt() + size.height)
    }
}
