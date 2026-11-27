package io.yamsergey.dta.studio.toolwindow

import java.awt.Component

/** D-108: how much room the title bar really leaves a component placed in it. */
object HeaderRoom {
    private const val LEVELS = 6

    /**
     * Width from [bar]'s left edge to the nearest part to its right in the header (beside it, on its rows):
     * the IDE lays a tool window's tab actions out wider than what shows, the title actions (gear, checkbox,
     * hide) on top of the rest. Null when nothing is found.
     */
    fun available(bar: Component): Int? {
        var limit: Int? = null
        var child: Component = bar
        var barX = 0
        var level = 0
        while (level < LEVELS) {
            val parent = child.parent ?: break
            barX += child.x
            val rowTop = child.y; val rowBottom = child.y + child.height
            for (s in parent.components) {
                if (s === child || !s.isVisible || s.width <= 0) continue
                if (s.y >= rowBottom || s.y + s.height <= rowTop) continue
                if (s.x <= child.x) continue
                val room = s.x - barX
                if (room > 0) limit = minOf(limit ?: room, room)
            }
            // The coordinates of the next level start at the parent's own place.
            child = parent
            level++
        }
        return limit
    }
}
