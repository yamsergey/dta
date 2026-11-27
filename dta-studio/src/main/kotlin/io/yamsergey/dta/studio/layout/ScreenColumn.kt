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
    private val caption = JBLabel(" ").apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(2, 8) }

    init {
        add(picture, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(caption, BorderLayout.CENTER)
            add(ActionLink("Next under pointer (N)") { onNext() }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    fun setCaption(text: String) { caption.text = text.ifEmpty { " " } }
}
