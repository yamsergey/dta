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
 * The width always follows the viewport. Parts asking to be scrolled into view do not move it (only the wheel and the bar do).
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
        // A row asked to be visible inside the table or the list scrolls that table or list only; the board opens from the top and stays
        // where the person scrolled it (the robot saw a selection move it by 72 px).
        override fun scrollRectToVisible(r: java.awt.Rectangle?) {}
        // Never less than the parts' minimums, even when their preferred sizes add up to less.
        override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().let { java.awt.Dimension(it.width, maxOf(it.height, minimumSize.height)) }
    }.also { it.add(content, BorderLayout.CENTER) },
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
) {
    // IntelliJ's text areas (JBTextArea.scrollRectToVisible) scroll the enclosing JBViewport themselves, not through their parents:
    // the viewport itself ignores a part's request (the robot saw a caret in a text area move the board by 72 px).
    override fun createViewport(): javax.swing.JViewport = object : com.intellij.ui.components.JBViewport() {
        override fun scrollRectToVisible(contentRect: java.awt.Rectangle?) {}
    }

    init {
        border = null
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
    }
}
