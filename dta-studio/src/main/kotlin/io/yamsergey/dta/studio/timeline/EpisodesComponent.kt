package io.yamsergey.dta.studio.timeline

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.timeline.EpisodeRow
import io.yamsergey.dta.present.timeline.EpisodesView
import io.yamsergey.dta.protocol.timeline.EpisodeLanes
import io.yamsergey.dta.protocol.timeline.EpisodeStep
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
 * The Episodes view (feature 045): a line per episode, and under an open one its sequence diagram.
 * Everything drawn comes from [EpisodesView]; causes are never decided here.
 */
class EpisodesComponent(
    private val onToggle: (String) -> Unit,
    private val onOpen: (String) -> Unit,
) : JComponent(), Scrollable {
    private var view = EpisodesView()
    private var rows: List<EpisodeRow> = emptyList()
    private var positions: Map<String, Double> = emptyMap()
    private var tops = IntArray(0)
    private var empty = ""
    private var hovered = -1

    private val headHeight get() = JBUI.scale(HEAD_PX)
    private val stepHeight get() = JBUI.scale(STEP_PX)

    init {
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when (val r = rows.getOrNull(rowAt(e.y))) {
                    is EpisodeRow.Head -> onToggle(r.episode.id)
                    is EpisodeRow.Step -> r.step.entryId?.let(onOpen)
                    null -> Unit
                }
            }

            override fun mouseMoved(e: MouseEvent) = hover(rowAt(e.y))
            override fun mouseExited(e: MouseEvent) = hover(-1)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun show(next: EpisodesView, emptyText: String) {
        view = next
        empty = emptyText
        rows = next.rows
        positions = next.positions()
        tops = IntArray(rows.size + 1)
        rows.forEachIndexed { i, r -> tops[i + 1] = tops[i] + if (r is EpisodeRow.Head) headHeight else stepHeight }
        hovered = -1
        revalidate()
        repaint()
    }

    /** Where a lane stands at this component's width; the lanes' titles above line up with it. */
    fun laneX(id: String): Int {
        val gutter = JBUI.scale(GUTTER_PX)
        return gutter + ((width - gutter) * (positions[id] ?: positions[EpisodeLanes.APP] ?: 0.0)).toInt()
    }

    private fun rowAt(y: Int): Int {
        if (rows.isEmpty() || y < 0 || y >= tops.last()) return -1
        val i = tops.binarySearch(y)
        return if (i >= 0) i else -i - 2
    }

    private fun hover(i: Int) {
        if (i == hovered) return
        hovered = i
        val r = rows.getOrNull(i)
        cursor = if (r is EpisodeRow.Head || (r as? EpisodeRow.Step)?.step?.entryId != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }

    override fun getToolTipText(e: MouseEvent): String? = when (val r = rows.getOrNull(rowAt(e.y))) {
        is EpisodeRow.Head -> listOf(r.episode.title, r.meta, r.stream).filter { it.isNotEmpty() }.joinToString(" · ")
        is EpisodeRow.Step -> r.label + if (r.step.entryId != null) " (click to open in Network)" else ""
        null -> null
    }

    override fun getToolTipLocation(e: MouseEvent): Point? = null

    override fun getPreferredSize() = Dimension(0, maxOf(tops.lastOrNull() ?: 0, JBUI.scale(HEAD_PX)))
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = if (o == SwingConstants.VERTICAL) stepHeight else JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = if (o == SwingConstants.VERTICAL) r.height else r.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = rows.isEmpty()

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = UIUtil.getPanelBackground()
            g.fillRect(0, 0, width, height)
            if (rows.isEmpty()) {
                g.color = JBColor.GRAY
                g.drawString(empty, JBUI.scale(12), JBUI.scale(24))
                return
            }
            val clip = g.clipBounds
            val from = maxOf(rowAt(clip.y), 0)
            for (i in from until rows.size) {
                if (tops[i] > clip.y + clip.height) break
                when (val r = rows[i]) {
                    is EpisodeRow.Head -> head(g, r, tops[i], i == hovered)
                    is EpisodeRow.Step -> step(g, r, tops[i], i == hovered)
                }
            }
        } finally {
            g.dispose()
        }
    }

    private fun head(g: Graphics2D, r: EpisodeRow.Head, y: Int, hover: Boolean) {
        val h = headHeight
        if (hover) {
            g.color = HOVER
            g.fillRect(0, y, width, h)
        }
        g.color = JBColor.border()
        g.drawLine(0, y + h - 1, width, y + h - 1)
        val icon = if (r.open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        icon.paintIcon(this, g, JBUI.scale(4), y + (h - icon.iconHeight) / 2)
        val base = g.font
        val fm = g.getFontMetrics(base)
        val text = y + (h + fm.ascent - fm.descent) / 2
        var right = width - JBUI.scale(10)
        if (r.stream.isNotEmpty()) {
            val s = fit(r.stream, fm, width / 3)
            right -= fm.stringWidth(s)
            g.color = DtaColors.of("kind-ws")
            g.drawString(s, right, text)
            right -= JBUI.scale(GAP_PX)
        }
        g.color = JBColor.GRAY
        g.drawString(r.time, JBUI.scale(24), text)
        val titleX = JBUI.scale(GUTTER_PX + 14)
        val bold = base.deriveFont(Font.BOLD)
        val titleWidth = g.getFontMetrics(bold).stringWidth(r.episode.title)
        if (width >= JBUI.scale(NARROW_PX) && r.meta.isNotEmpty()) {
            val room = right - (titleX + minOf(titleWidth, (right - titleX) / 2)) - JBUI.scale(GAP_PX)
            val s = fit(r.meta, fm, room)
            right -= fm.stringWidth(s)
            // Only the failures are in danger's colour: a whole red line would read as a whole failure.
            val failed = "${r.episode.failures} failed"
            val red = if (r.episode.failures > 0 && s.startsWith(failed)) failed else ""
            g.color = DtaColors.of("danger")
            g.drawString(red, right, text)
            g.color = JBColor.GRAY
            g.drawString(s.removePrefix(red), right + fm.stringWidth(red), text)
            right -= JBUI.scale(GAP_PX)
        } else if (r.episode.failures > 0) {
            val s = "${r.episode.failures} failed"
            right -= fm.stringWidth(s)
            g.color = DtaColors.of("danger")
            g.drawString(s, right, text)
            right -= JBUI.scale(GAP_PX)
        }
        g.font = bold
        g.color = UIUtil.getLabelForeground()
        g.drawString(fit(r.episode.title, g.fontMetrics, right - titleX), titleX, text)
        g.font = base
    }

    private fun step(g: Graphics2D, r: EpisodeRow.Step, y: Int, hover: Boolean) {
        val h = stepHeight
        val s = r.step
        if (hover && s.entryId != null) {
            g.color = HOVER
            g.fillRect(0, y, width, h)
        }
        g.color = GRID
        positions.keys.forEach { val x = laneX(it); g.drawLine(x, y, x, y + h) }
        val base = g.font
        val small = base.deriveFont(base.size2D - 1f)
        g.font = small
        g.color = JBColor.GRAY
        val fm = g.fontMetrics
        g.drawString(r.time, JBUI.scale(8), y + (h + fm.ascent - fm.descent) / 2)
        val to = s.to
        if (to == null) note(g, r, s, y, h) else arrow(g, r, s, to, y, h)
        g.font = base
    }

    private fun note(g: Graphics2D, r: EpisodeRow.Step, s: EpisodeStep, y: Int, h: Int) {
        val fm = g.fontMetrics
        val pad = JBUI.scale(7)
        val label = fit(r.label, fm, width - JBUI.scale(GUTTER_PX) - 2 * pad)
        val w = fm.stringWidth(label) + 2 * pad
        val min = JBUI.scale(GUTTER_PX)
        val x = (laneX(s.from) - w / 2).coerceIn(min, maxOf(min, width - w - JBUI.scale(2)))
        val top = y + JBUI.scale(2)
        val ph = h - JBUI.scale(4)
        g.color = TAG
        g.fillRoundRect(x, top, w, ph, JBUI.scale(6), JBUI.scale(6))
        g.color = if (s.failed) DtaColors.of("danger") else UIUtil.getLabelForeground()
        g.drawString(label, x + pad, top + (ph + fm.ascent - fm.descent) / 2)
    }

    private fun arrow(g: Graphics2D, r: EpisodeRow.Step, s: EpisodeStep, to: String, y: Int, h: Int) {
        val a = laneX(s.from)
        val b = laneX(to)
        val line = y + h - JBUI.scale(5)
        val color: Color = when {
            s.failed -> DtaColors.of("danger")
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
        val label = fit(r.label, fm, span - JBUI.scale(4))
        g.drawString(label, left + (span - fm.stringWidth(label)) / 2, line - JBUI.scale(3))
    }

    private fun fit(text: String, fm: FontMetrics, room: Int): String {
        if (fm.stringWidth(text) <= room) return text
        if (room <= fm.stringWidth("…")) return ""
        var n = text.length
        while (n > 0 && fm.stringWidth(text.take(n) + "…") > room) n--
        return text.take(n) + "…"
    }

    /** The lanes' titles; stays above the scrolling diagram and lines up with it. */
    inner class Titles : JComponent() {
        override fun getPreferredSize() = Dimension(0, JBUI.scale(TITLES_PX))

        override fun paintComponent(g0: Graphics) {
            val g = g0.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val fm = g.fontMetrics
                val y = (height + fm.ascent - fm.descent) / 2
                g.color = JBColor.border()
                g.drawLine(0, height - 1, width, height - 1)
                g.color = JBColor.GRAY
                view.lanes.forEach { lane ->
                    val w = fm.stringWidth(lane.title)
                    val x = (laneX(lane.id) - w / 2).coerceIn(0, maxOf(0, width - w - JBUI.scale(4)))
                    g.drawString(lane.title, x, y)
                }
            } finally {
                g.dispose()
            }
        }
    }

    private companion object {
        const val HEAD_PX = 26
        const val STEP_PX = 26
        const val TITLES_PX = 24
        const val GUTTER_PX = 62
        const val GAP_PX = 14
        const val NARROW_PX = 700
        val GRID = JBColor(Color(0xEBECF0), Color(0x393B40))
        val TAG = JBColor(Color(0xEBECF0), Color(0x393B40))
        val HOVER = JBColor(Color(0xF0F1F2), Color(0x2B2D30))
    }
}
