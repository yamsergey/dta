package io.yamsergey.dta.studio.data

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.data.DiBrowser
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Feature 037: a DI layout drawn — columns of boxes left to right (dependencies left of what
 * needs them), a line from each box to each it depends on. Created instances in the normal
 * colour, not-yet-created and factories greyed, the chosen one outlined. Click a box to choose it.
 */
class DiGraphCanvas(private val di: DiBrowser, private val onSelect: (String) -> Unit) : JComponent() {
    private var layout = DiBrowser.Layout(emptyList(), emptyList())
    private var selected: String? = null
    private val boxes = LinkedHashMap<String, Rectangle>()

    init {
        isOpaque = true
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { boxes.entries.firstOrNull { it.value.contains(e.point) }?.let { onSelect(it.key) } }
        })
    }

    fun show(l: DiBrowser.Layout, chosen: String?) {
        layout = l
        selected = chosen
        place()
        revalidate()
        repaint()
    }

    private fun place() {
        boxes.clear()
        val fm = getFontMetrics(font ?: UIUtil.getLabelFont())
        val pad = JBUI.scale(8); val rowGap = JBUI.scale(10); val colGap = JBUI.scale(56)
        val h = fm.height * 2 + pad
        var x = pad
        layout.columns.forEach { column ->
            // A box is at most MAX_TEXT wide: long names (Metro's multibinding maps) end in "…".
            val w = (column.maxOfOrNull { id -> di.node(id)?.let { maxOf(fm.stringWidth(it.title), fm.stringWidth(it.binding.name)) } ?: 40 } ?: 80).coerceAtMost(JBUI.scale(MAX_TEXT)) + pad * 2
            column.forEachIndexed { row, id -> boxes[id] = Rectangle(x, pad + row * (h + rowGap), w, h) }
            x += w + colGap
        }
        val height = (boxes.values.maxOfOrNull { it.y + it.height } ?: 0) + pad
        preferredSize = Dimension(x, height)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, width, height)
        val fm = g.fontMetrics
        g.stroke = BasicStroke(JBUI.scale(1).toFloat())
        g.color = JBColor.GRAY
        layout.edges.forEach { (from, to) ->
            val a = boxes[from] ?: return@forEach; val b = boxes[to] ?: return@forEach
            // From the dependent's left side to the dependency's right side (an arrowhead there).
            val x1 = a.x; val y1 = a.y + a.height / 2; val x2 = b.x + b.width; val y2 = b.y + b.height / 2
            g.drawLine(x1, y1, x2, y2)
            val s = JBUI.scale(5)
            g.fillPolygon(intArrayOf(x2, x2 + s, x2 + s), intArrayOf(y2, y2 - s / 2 - 1, y2 + s / 2 + 1), 3)
        }
        boxes.forEach { (id, r) ->
            val n = di.node(id) ?: return@forEach
            val created = n.binding.runtimeType != null
            g.color = if (id == selected) JBColor(0xDDE8FF, 0x2D3B55) else UIUtil.getListBackground()
            g.fillRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(8), JBUI.scale(8))
            g.color = if (id == selected) JBColor(0x3574F0, 0x548AF7) else JBColor.border()
            g.drawRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(8), JBUI.scale(8))
            g.color = if (created) UIUtil.getLabelForeground() else JBColor.GRAY
            val room = r.width - JBUI.scale(16)
            g.drawString(fit(n.title, fm, room), r.x + JBUI.scale(8), r.y + fm.ascent + JBUI.scale(3))
            g.color = JBColor.GRAY
            g.drawString(fit(n.binding.name, fm, room), r.x + JBUI.scale(8), r.y + fm.ascent + fm.height + JBUI.scale(3))
        }
    }

    private fun fit(text: String, fm: java.awt.FontMetrics, room: Int): String {
        if (fm.stringWidth(text) <= room) return text
        var t = text
        while (t.isNotEmpty() && fm.stringWidth("$t…") > room) t = t.dropLast(1)
        return "$t…"
    }

    /** Tooltip: the full type and name of the box under the mouse. */
    override fun getToolTipText(e: MouseEvent): String? = boxes.entries.firstOrNull { it.value.contains(e.point) }?.let { di.node(it.key) }?.let { "${it.binding.type} · ${it.binding.name} · ${it.state}" }

    companion object { private const val MAX_TEXT = 200 }
}
