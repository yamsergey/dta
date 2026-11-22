package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension

/** A read-only sentence that wraps at its width (under a field, in a narrow detail), selectable. */
class WrappingText(text: String, color: Color) : JBTextArea(text) {
    init {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(1, 0)
        font = UIUtil.getLabelFont()
        foreground = color
        alignmentX = Component.LEFT_ALIGNMENT
    }

    // As wide as the container gives — never wider than it (a long sentence must not widen a pane or
    // move a splitter); as tall as the lines at that width.
    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(40), super.getPreferredSize().height)
    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), super.getPreferredSize().height)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, super.getPreferredSize().height)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val resized = width != this.width
        super.setBounds(x, y, width, height)
        // The height follows the new width on the next layout.
        if (resized) javax.swing.SwingUtilities.invokeLater { revalidate() }
    }
}
