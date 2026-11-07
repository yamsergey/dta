package io.yamsergey.dta.studio.timeline

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.timeline.LaneTitles
import io.yamsergey.dta.present.timeline.SequenceItem
import io.yamsergey.dta.present.timeline.SequenceText
import io.yamsergey.dta.present.timeline.SequenceView
import io.yamsergey.dta.protocol.timeline.SequenceFlow
import io.yamsergey.dta.protocol.timeline.SequenceLanes
import io.yamsergey.dta.protocol.timeline.SequenceStep
import io.yamsergey.dta.studio.ui.DtaColors
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The Sequence view (feature 048): one flat sequence diagram, a row per step, nothing grouped.
 * Everything drawn comes from [SequenceView]; causes are never decided here. Feature 049: a flow
 * is a line above its first row and a tint on its rows; nothing moves or hides because of it.
 */
class SequenceComponent(
    private val onOpen: (String) -> Unit,
    private val onFlowMenu: (SequenceFlow, MouseEvent) -> Unit = { _, _ -> },
) : JComponent(), Scrollable {
    private var view = SequenceView()
    private var items: List<SequenceItem> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private var positions: Map<String, Double> = emptyMap()
    private var empty = ""
    private var hovered = -1

    private val rowHeight get() = JBUI.scale(ROW_PX)
    private val gutter get() = JBUI.scale(GUTTER_PX)

    init {
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) stepAt(e.y)?.entryId?.let(onOpen)
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            override fun mouseMoved(e: MouseEvent) = hover(rowAt(e.y))
            override fun mouseExited(e: MouseEvent) = hover(-1)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun show(next: SequenceView, emptyText: String) {
        view = next
        empty = emptyText
        items = next.items
        names = next.flowNames
        positions = next.positions()
        hovered = -1
        revalidate()
        repaint()
    }

    /** Where an item stands, to bring it into view. */
    fun rowBounds(i: Int) = Rectangle(0, i * rowHeight, width, rowHeight)

    /** Where a lane stands at this component's width; the lanes' titles above line up with it. */
    fun laneX(id: String): Int = gutter + ((width - gutter) * (positions[id] ?: positions[SequenceLanes.APP] ?: 0.0)).toInt()

    private fun rowAt(y: Int): Int = if (y < 0 || rowHeight == 0) -1 else (y / rowHeight).takeIf { it < items.size } ?: -1

    private fun stepAt(y: Int): SequenceStep? = (items.getOrNull(rowAt(y)) as? SequenceItem.Row)?.step

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        (items.getOrNull(rowAt(e.y)) as? SequenceItem.Line)?.let { onFlowMenu(it.flow, e) }
    }

    private fun hover(i: Int) {
        if (i == hovered) return
        hovered = i
        cursor = if ((items.getOrNull(i) as? SequenceItem.Row)?.step?.entryId != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }

    override fun getToolTipText(e: MouseEvent): String? = when (val item = items.getOrNull(rowAt(e.y))) {
        is SequenceItem.Row -> SequenceText.hover(item.step, names) + if (item.step.entryId != null) "\n(click to open in Network)" else ""
        is SequenceItem.Line -> SequenceText.flowTitle(item.flow) + flowMeta(item.flow) + "\nRows are in it by time, not by a proved cause."
        null -> null
    }?.let { "<html>" + it.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>" }

    private fun flowMeta(f: SequenceFlow) = SequenceText.flowMeta(f, view.page?.startWallMs ?: 0, view.startMs)

    override fun getToolTipLocation(e: MouseEvent): Point? = null

    override fun getPreferredSize() = Dimension(0, maxOf(items.size * rowHeight, rowHeight))
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = if (o == SwingConstants.VERTICAL) rowHeight else JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = if (o == SwingConstants.VERTICAL) r.height else r.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = items.isEmpty()

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = UIUtil.getPanelBackground()
            g.fillRect(0, 0, width, height)
            if (items.isEmpty()) {
                g.color = JBColor.GRAY
                g.drawString(empty, JBUI.scale(12), JBUI.scale(24))
                return
            }
            val clip = g.clipBounds
            val from = maxOf(clip.y / rowHeight, 0)
            val to = minOf((clip.y + clip.height) / rowHeight, items.size - 1)
            for (i in from..to) {
                when (val item = items[i]) {
                    is SequenceItem.Row -> row(g, item, i * rowHeight, i)
                    is SequenceItem.Line -> line(g, item.flow, i * rowHeight)
                }
            }
        } finally {
            g.dispose()
        }
    }

    /** A flow's line: its name, then when and how many, over the tint its rows have. */
    private fun line(g: Graphics2D, f: SequenceFlow, y: Int) {
        val h = rowHeight
        g.color = FLOW
        g.fillRect(0, y, width, h)
        g.color = GRID
        positions.keys.forEach { val x = laneX(it); g.drawLine(x, y, x, y + h) }
        val base = g.font
        val left = JBUI.scale(8)
        val room = width - 2 * left
        g.font = base.deriveFont(Font.BOLD)
        val title = fit(SequenceText.flowTitle(f), g.fontMetrics, room)
        val baseline = y + (h + g.fontMetrics.ascent - g.fontMetrics.descent) / 2
        g.color = UIUtil.getLabelForeground()
        g.drawString(title, left, baseline)
        val used = g.fontMetrics.stringWidth(title)
        g.font = base
        g.color = JBColor.GRAY
        g.drawString(fit(flowMeta(f), g.fontMetrics, room - used), left + used, baseline)
    }

    private fun row(g: Graphics2D, item: SequenceItem.Row, y: Int, i: Int) {
        val s = item.step
        val h = rowHeight
        when {
            item.index == view.currentRow -> { g.color = UIUtil.getListSelectionBackground(false); g.fillRect(0, y, width, h) }
            s.hit -> { g.color = HIT; g.fillRect(0, y, width, h) }
            i == hovered && s.entryId != null -> { g.color = HOVER; g.fillRect(0, y, width, h) }
            item.flow != null -> { g.color = FLOW; g.fillRect(0, y, width, h) }
        }
        g.color = GRID
        positions.keys.forEach { val x = laneX(it); g.drawLine(x, y, x, y + h) }
        if (s.boundary) {
            g.color = JBColor.border()
            g.drawLine(0, y + h / 2, width, y + h / 2)
        }
        val base = g.font
        g.font = base.deriveFont(base.size2D - 1f)
        g.color = JBColor.GRAY
        val fm = g.fontMetrics
        g.drawString(view.time(s), JBUI.scale(8), y + (h + fm.ascent - fm.descent) / 2)
        val to = s.to
        if (to == null) note(g, s, y, h) else arrow(g, s, to, y, h)
        g.font = base
    }

    private fun note(g: Graphics2D, s: SequenceStep, y: Int, h: Int) {
        val fm = g.fontMetrics
        val pad = JBUI.scale(7)
        val label = fit(SequenceText.label(s), fm, width - gutter - 2 * pad)
        val w = fm.stringWidth(label) + 2 * pad
        val x = (laneX(s.from) - w / 2).coerceIn(gutter, maxOf(gutter, width - w - JBUI.scale(2)))
        val top = y + JBUI.scale(2)
        val ph = h - JBUI.scale(4)
        g.color = TAG
        g.fillRoundRect(x, top, w, ph, JBUI.scale(6), JBUI.scale(6))
        g.color = if (s.failed) DtaColors.of("danger") else if (s.boundary) DtaColors.of("caveat") else UIUtil.getLabelForeground()
        g.drawString(label, x + pad, top + (ph + fm.ascent - fm.descent) / 2)
    }

    private fun arrow(g: Graphics2D, s: SequenceStep, to: String, y: Int, h: Int) {
        val a = laneX(s.from)
        val b = laneX(to)
        val line = y + h - JBUI.scale(5)
        val color: Color = when {
            s.failed -> DtaColors.of("danger")
            s.from == SequenceLanes.SOCKET -> DtaColors.of("kind-ws")
            s.back -> JBColor.GRAY
            else -> UIUtil.getLabelForeground()
        }
        val saved = g.stroke
        g.color = color
        g.stroke = if (s.back) BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(JBUI.scale(4).toFloat(), JBUI.scale(3).toFloat()), 0f) else BasicStroke(1f)
        g.drawLine(a, line, b, line)
        g.stroke = saved
        val tip = JBUI.scale(6)
        val half = JBUI.scale(3)
        val dir = if (b > a) -1 else 1
        g.fillPolygon(Polygon(intArrayOf(b, b + dir * tip, b + dir * tip), intArrayOf(line, line - half, line + half), 3))
        g.font = Font(Font.MONOSPACED, Font.PLAIN, g.font.size)
        val fm = g.fontMetrics
        val span = kotlin.math.abs(b - a)
        val left = minOf(a, b)
        val label = fit(SequenceText.label(s), fm, span - JBUI.scale(4))
        g.drawString(label, left + (span - fm.stringWidth(label)) / 2, line - JBUI.scale(3))
    }

    private fun fit(text: String, fm: FontMetrics, room: Int): String {
        if (fm.stringWidth(text) <= room) return text
        if (room <= fm.stringWidth("…")) return ""
        var n = text.length
        while (n > 0 && fm.stringWidth(text.take(n) + "…") > room) n--
        return text.take(n) + "…"
    }

    /** The lanes' titles, each cut to its room with the full name on hover; stays above the scrolling diagram and lines up with it. */
    inner class Titles : JComponent() {
        init {
            toolTipText = ""
        }

        private fun rooms() = LaneTitles.of(view.lanes.map { it.id }, width) { laneX(it) }

        override fun getPreferredSize() = Dimension(0, JBUI.scale(TITLES_PX))

        override fun getToolTipText(e: MouseEvent): String? {
            val room = rooms().firstOrNull { e.x >= it.from && e.x < it.to } ?: return null
            return view.lanes.firstOrNull { it.id == room.id }?.title
        }

        override fun getToolTipLocation(e: MouseEvent): Point? = null

        override fun paintComponent(g0: Graphics) {
            val g = g0.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val fm = g.fontMetrics
                val y = (height + fm.ascent - fm.descent) / 2
                g.color = JBColor.border()
                g.drawLine(0, height - 1, width, height - 1)
                g.color = JBColor.GRAY
                val titles = view.lanes.associate { it.id to it.title }
                rooms().forEach { room ->
                    val text = fit(titles[room.id].orEmpty(), fm, room.to - room.from - JBUI.scale(TITLE_PAD_PX))
                    g.drawString(text, room.from + (room.to - room.from - fm.stringWidth(text)) / 2, y)
                }
            } finally {
                g.dispose()
            }
        }
    }

    private companion object {
        const val ROW_PX = 26
        const val TITLES_PX = 24
        const val GUTTER_PX = 62
        const val TITLE_PAD_PX = 8
        val GRID = JBColor(Color(0xEBECF0), Color(0x393B40))
        val TAG = JBColor(Color(0xEBECF0), Color(0x393B40))
        val HOVER = JBColor(Color(0xF0F1F2), Color(0x2B2D30))
        val HIT = JBColor(Color(0xFFF6D6), Color(0x3D3724))
        val FLOW = JBColor(Color(0xEDF3FF), Color(0x25324D))
    }
}
