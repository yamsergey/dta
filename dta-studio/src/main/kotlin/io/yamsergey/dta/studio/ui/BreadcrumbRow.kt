package io.yamsergey.dta.studio.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The selected node's path on one line: ancestors are links, the last item (the selected one) is bold and whole.
 * Short of room the middle ones become one "…" ([BreadcrumbFit]); the whole path is in the tooltip. Never hard-cut.
 */
class BreadcrumbRow(private val names: List<String>, onAncestor: (depth: Int) -> Unit) : JPanel() {
    internal val items: List<JComponent> = names.mapIndexed { i, name ->
        if (i == names.lastIndex) JBLabel(name).apply { font = font.deriveFont(Font.BOLD) } else ActionLink(name) { onAncestor(i + 1) }
    }
    private val separators = List(names.size) { JBLabel("›").apply { foreground = JBColor.GRAY } }
    internal val ellipsis = JBLabel("…").apply { foreground = JBColor.GRAY }
    private val gap get() = JBUI.scale(4)

    init {
        isOpaque = false
        toolTipText = names.joinToString(" › ")
        items.forEach { add(it) }; separators.forEach { add(it) }; add(ellipsis)
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}
            override fun removeLayoutComponent(comp: java.awt.Component?) {}
            override fun preferredLayoutSize(parent: Container) = Dimension(natural(), items.maxOf { it.preferredSize.height })
            override fun minimumLayoutSize(parent: Container) = Dimension(0, items.maxOf { it.preferredSize.height })
            override fun layoutContainer(parent: Container) = place()
        }
    }

    private fun sep() = separators.first().preferredSize.width + 2 * gap

    private fun natural() = items.sumOf { it.preferredSize.width } + sep() * (names.size - 1).coerceAtLeast(0)

    private fun place() {
        val widths = items.map { it.preferredSize.width }
        val kept = BreadcrumbFit.kept(widths, sep(), ellipsis.preferredSize.width, width)
        val h = height
        items.forEach { it.isVisible = false }; separators.forEach { it.isVisible = false }; ellipsis.isVisible = false
        var x = 0
        fun put(c: JComponent, w: Int) { c.isVisible = true; val ph = c.preferredSize.height; c.setBounds(x, (h - ph) / 2, w, ph); x += w }
        fun separator(i: Int) { x += gap; put(separators[i], separators[i].preferredSize.width); x += gap }
        kept.forEachIndexed { k, i ->
            if (k > 0) {
                if (kept[k - 1] + 1 != i) { separator(kept[k - 1]); put(ellipsis, ellipsis.preferredSize.width) }
                separator(i - 1)
            } else if (i > 0) { put(ellipsis, ellipsis.preferredSize.width); separator(0) }
            // The last item is whole when it can be; when even alone it is wider than the row it takes what is left (its own ellipsis).
            put(items[i], minOf(widths[i], (width - x).coerceAtLeast(0)))
        }
        items.last().toolTipText = names.joinToString(" › ")
    }
}
