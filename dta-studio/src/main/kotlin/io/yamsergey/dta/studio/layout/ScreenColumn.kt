package io.yamsergey.dta.studio.layout

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The picture with the caption row under it (the hovered element's name, "Next under pointer").
 * Feature 057: the row is one line high whether empty or not, so hovering never resizes the picture.
 */
class ScreenColumn(picture: JComponent, onNext: () -> Unit) : JPanel(BorderLayout()) {
    /** Asks for no width of its own (D-123): a long name is cut with an ellipsis, the full text is the tooltip. */
    private val caption = object : JBLabel(" ") {
        override fun getMinimumSize() = java.awt.Dimension(0, super.getMinimumSize().height)
        override fun getPreferredSize() = java.awt.Dimension(0, super.getPreferredSize().height)
        fun show(text: String) { setText(text); toolTipText = text.takeIf { it.isNotBlank() } }
    }.apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(2, 8) }

    init {
        add(picture, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(caption, BorderLayout.CENTER)
            add(ActionLink("Next under pointer (N)") { onNext() }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    fun setCaption(text: String) = caption.show(text.ifEmpty { " " })
}
