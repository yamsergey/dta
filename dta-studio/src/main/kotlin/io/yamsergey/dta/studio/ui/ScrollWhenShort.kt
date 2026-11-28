package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/**
 * A board that takes the room it is given while that is at least the sum of its parts' minimums, and scrolls vertically when it is
 * shorter: the content keeps its minimum height, so no part is squeezed below its minimum or drawn over another (a low tool window).
 * The width always follows the viewport.
 */
class ScrollWhenShort(private val content: JComponent) : JBScrollPane(
    object : JPanel(BorderLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle?, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle?, o: Int, d: Int) = (r?.height ?: JBUI.scale(64))
        override fun getScrollableTracksViewportWidth() = true
        // Tall enough for the minimums: the board fills the viewport; shorter: it keeps its minimum and the bar appears.
        override fun getScrollableTracksViewportHeight(): Boolean {
            val port = parent as? javax.swing.JViewport ?: return true
            return port.height >= minimumSize.height
        }
        override fun getMinimumSize() = (getComponent(0) as JComponent).minimumSize
        // Never less than the parts' minimums, even when their preferred sizes add up to less.
        override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().let { java.awt.Dimension(it.width, maxOf(it.height, minimumSize.height)) }
    }.also { it.add(content, BorderLayout.CENTER) },
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
) {
    init {
        border = null
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
    }
}
