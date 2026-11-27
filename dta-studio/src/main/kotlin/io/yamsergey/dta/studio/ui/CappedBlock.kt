package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

/**
 * Lines of text above or below a table that never take more than [fraction] of their parent's height: the lines
 * wrap to the width, and past the cap the block scrolls, so the table keeps its rows (D-138).
 */
class CappedBlock(private val parts: List<JComponent>, private val fraction: Double = 0.25, private val maxLines: Int = Int.MAX_VALUE) : JBScrollPane(
    Page().apply { border = com.intellij.util.ui.JBUI.Borders.empty(); parts.forEach { (it as? javax.swing.text.JTextComponent)?.border = null; line(it) } },
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
) {
    init {
        border = null
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
        // Whole lines: it scrolls a line at a time and its cap is a number of lines, so no line is half-cut at an edge.
        verticalScrollBar.unitIncrement = lineHeight()
        parts.filterIsInstance<javax.swing.text.JTextComponent>().forEach { text ->
            // A text set into the area must not scroll the block to the caret (the IDE showed the end of the text, 19 px off a line)...
            (text.caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
            // ...a new text opens at its top.
            text.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = toTop()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = toTop()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
            })
        }
        // Whatever moves it (the wheel, a bar drag) ends on a line.
        viewport.addChangeListener {
            val line = lineHeight()
            val y = viewport.viewPosition.y
            val snapped = (y + line / 2) / line * line
            if (snapped != y && !snapping) {
                snapping = true
                try { viewport.viewPosition = java.awt.Point(viewport.viewPosition.x, snapped.coerceAtMost(maxOf(0, viewport.viewSize.height - viewport.extentSize.height))) } finally { snapping = false }
            }
        }
    }

    private var snapping = false

    private fun toTop() = javax.swing.SwingUtilities.invokeLater { viewport.viewPosition = java.awt.Point(0, 0) }

    /** The line of the text drawn (its own font), so the block's height and its scrolling are whole lines of it. */
    private fun lineHeight(): Int {
        val text = parts.firstOrNull { it is javax.swing.text.JTextComponent } ?: this
        return text.getFontMetrics(text.font).height.coerceAtLeast(1)
    }

    override fun getPreferredSize(): Dimension {
        // The insets are the look's (the LaF puts a 1 px border back whatever is set here), so they are added to whole lines.
        val frame = insets.top + insets.bottom
        val natural = viewport.view.preferredSize.height + frame
        val parentHeight = parent?.height ?: 0
        val line = lineHeight()
        val byShare = if (parentHeight > 0) maxOf((parentHeight * fraction).toInt() / line, (MIN + line - 1) / line) else Int.MAX_VALUE
        val lines = minOf(byShare, maxLines)
        val cap = if (lines == Int.MAX_VALUE) natural else lines * line + frame
        return Dimension(super.getPreferredSize().width, minOf(natural, cap))
    }

    override fun getMinimumSize(): Dimension = Dimension(0, minOf(MIN, preferredSize.height))

    private companion object { const val MIN = 24 }
}
