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
    internal val captionLabel get() = caption
    private val caption = object : JBLabel(" ") {
        override fun getMinimumSize() = java.awt.Dimension(0, super.getMinimumSize().height)
        override fun getPreferredSize() = java.awt.Dimension(0, super.getPreferredSize().height)
        fun show(text: String) { setText(text); toolTipText = text.takeIf { it.isNotBlank() } }
    }.apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(2, 8) }

    private val next = ActionLink("Next under pointer (N)") { onNext() }.apply { toolTipText = "Next under pointer (N)" }

    init {
        add(picture, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(caption, BorderLayout.CENTER)
            add(next, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        // D-144: in a narrow column the link is short, so the caption keeps the element's class name whole.
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) = fitLink()
        })
    }

    private fun fitLink() {
        val text = if (width in 1 until JBUI.scale(NARROW_BELOW)) "Next (N)" else "Next under pointer (N)"
        if (next.text != text) next.text = text
    }

    override fun doLayout() { fitLink(); super.doLayout() }

    companion object { const val NARROW_BELOW = 340 }

    fun setCaption(text: String) = caption.show(text.ifEmpty { " " })
}
