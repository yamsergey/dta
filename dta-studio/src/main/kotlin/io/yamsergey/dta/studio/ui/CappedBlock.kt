package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

/**
 * Lines of text above or below a table that never take more than [fraction] of their parent's height: the lines
 * wrap to the width, and past the cap the block scrolls, so the table keeps its rows (D-138).
 */
class CappedBlock(parts: List<JComponent>, private val fraction: Double = 0.25) : JBScrollPane(
    Page().apply { border = com.intellij.util.ui.JBUI.Borders.empty(); parts.forEach { line(it) } },
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
) {
    init {
        border = null
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val natural = viewport.view.preferredSize.height + insets.top + insets.bottom
        val parentHeight = parent?.height ?: 0
        val cap = if (parentHeight > 0) maxOf(MIN, (parentHeight * fraction).toInt()) else natural
        return Dimension(super.getPreferredSize().width, minOf(natural, cap))
    }

    override fun getMinimumSize(): Dimension = Dimension(0, minOf(MIN, preferredSize.height))

    private companion object { const val MIN = 24 }
}
