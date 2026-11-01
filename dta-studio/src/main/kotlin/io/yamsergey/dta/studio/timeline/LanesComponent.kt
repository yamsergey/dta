package io.yamsergey.dta.studio.timeline

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.timeline.ChainView
import io.yamsergey.dta.present.timeline.LaneLayout
import io.yamsergey.dta.present.timeline.TimelineChange
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.studio.ui.DtaColors
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Line2D
import javax.swing.JComponent

/**
 * Feature 017: the timeline's lanes — one row per source, marks by time, gaps and process ends
 * across, and the selected event's chain drawn between marks (exact solid, declared marked,
 * inferred dashed). Everything it draws is a [LaneLayout] and a [ChainView]; the wheel with a
 * modifier zooms, the wheel or a drag pans, a click selects (a group zooms in).
 */
class LanesComponent(private val controller: TimelineController) : JComponent() {
    private var state = TimelineTabState()
    private var layout: LaneLayout? = null
    private var dragX: Int? = null

    private val label get() = JBUI.scale(LABEL_PX)
    private val laneHeight get() = JBUI.scale(LANE_PX)
    private val axisHeight get() = JBUI.scale(AXIS_PX)

    init {
        isOpaque = true
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragX = e.x
                requestFocusInWindow()
            }

            override fun mouseReleased(e: MouseEvent) {
                dragX = null
            }

            override fun mouseDragged(e: MouseEvent) {
                val from = dragX ?: return
                dragX = e.x
                controller.change(TimelineChange.Pan(((from - e.x) * state.view.window.msPerPx).toLong()))
            }

            override fun mouseClicked(e: MouseEvent) {
                val mark = markAt(e.x, e.y) ?: return
                if (mark.count > 1) {
                    controller.change(TimelineChange.Zoom(0.25, timeAt(e.x)))
                } else {
                    controller.select(mark.eventIds.single())
                }
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown || e.isMetaDown) {
                    controller.change(TimelineChange.Zoom(if (e.wheelRotation > 0) 1.25 else 0.8, timeAt(e.x)))
                } else {
                    controller.change(TimelineChange.Pan((e.preciseWheelRotation * JBUI.scale(40) * state.view.window.msPerPx).toLong()))
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        isFocusable = true
    }

    fun show(s: TimelineTabState) {
        state = s
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val lanes = layout?.lanes?.size ?: state.view.sources.size.coerceAtLeast(1)
        val offLine = if (state.view.sources.any { it.state.name == "OFF" }) laneHeight else 0
        return Dimension(JBUI.scale(400), lanes * laneHeight + axisHeight + offLine + JBUI.scale(4))
    }

    private fun plotWidth() = (width - label).coerceAtLeast(1)

    private fun timeAt(x: Int): Long = (layout?.startMs ?: 0) + ((x - label) * state.view.window.msPerPx).toLong()

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            val l = LaneLayout.of(state.view, plotWidth())
            layout = l
            paintLanes(g2, l)
            paintSpans(g2, l)
            paintChain(g2, l)
            paintMarks(g2, l)
            paintAxis(g2, l)
        } finally {
            g2.dispose()
        }
    }

    private fun laneY(i: Int) = i * laneHeight

    private fun paintLanes(g: Graphics2D, l: LaneLayout) {
        g.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        l.lanes.forEachIndexed { i, lane ->
            if (i % 2 == 1) {
                g.color = DtaColors.of("surface-sunken")
                g.fillRect(label, laneY(i), plotWidth(), laneHeight)
            }
            g.color = JBColor.GRAY
            g.drawString(lane.source, JBUI.scale(8), laneY(i) + laneHeight / 2 + g.fontMetrics.ascent / 2 - 1)
        }
        g.color = DtaColors.of("line")
        g.drawLine(label, 0, label, l.lanes.size * laneHeight)
        if (l.off.isNotEmpty()) {
            g.color = JBColor.GRAY
            val y = l.lanes.size * laneHeight + axisHeight + g.fontMetrics.ascent + JBUI.scale(2)
            g.drawString("off: " + l.off.joinToString("  ·  ") { "${it.source} (${it.reason})" }, JBUI.scale(8), y)
        }
    }

    private fun paintSpans(g: Graphics2D, l: LaneLayout) {
        val bottom = l.lanes.size * laneHeight
        for (s in l.spans) {
            val x0 = label + s.x0.toInt()
            if (s.kind == Kinds.GAP && s.x1 > s.x0 + 1) {
                g.color = DtaColors.of("caveat-tint")
                g.fillRect(x0, 0, (s.x1 - s.x0).toInt(), bottom)
            }
            g.color = DtaColors.of("caveat")
            g.stroke = if (s.kind == Kinds.GAP) DASHED else BasicStroke(JBUI.scale(2).toFloat())
            g.drawLine(x0, 0, x0, bottom)
            g.stroke = BasicStroke(1f)
        }
    }

    /** Where each event id is drawn: (x, lane centre y). */
    private fun positions(l: LaneLayout): Map<String, Pair<Int, Int>> {
        val out = HashMap<String, Pair<Int, Int>>()
        l.lanes.forEachIndexed { i, lane -> lane.marks.forEach { m -> m.eventIds.forEach { out[it] = (label + m.x.toInt()) to (laneY(i) + laneHeight / 2) } } }
        return out
    }

    private fun paintChain(g: Graphics2D, l: LaneLayout) {
        val at = positions(l)
        for (line in state.chain.lines) {
            if (line.style == ChainView.Style.INFERRED && !state.inferred) continue
            val (x1, y1) = at[line.from] ?: continue
            val (x2, y2) = at[line.to] ?: continue
            when (line.style) {
                ChainView.Style.EXACT -> { g.color = UIUtil.getLabelForeground(); g.stroke = BasicStroke(JBUI.scale(3) / 2f) }
                ChainView.Style.DECLARED -> { g.color = DtaColors.of("accent"); g.stroke = BasicStroke(JBUI.scale(3) / 2f) }
                ChainView.Style.INFERRED -> { g.color = DtaColors.of("inferred"); g.stroke = DASHED }
            }
            g.draw(Line2D.Double(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble()))
            if (line.style == ChainView.Style.DECLARED) {
                val mx = (x1 + x2) / 2
                val my = (y1 + y2) / 2
                val r = JBUI.scale(3)
                g.fillPolygon(intArrayOf(mx, mx + r, mx, mx - r), intArrayOf(my - r, my, my + r, my), 4)
            }
        }
        g.stroke = BasicStroke(1f)
    }

    private fun paintMarks(g: Graphics2D, l: LaneLayout) {
        val r = JBUI.scale(4)
        l.lanes.forEachIndexed { i, lane ->
            val cy = laneY(i) + laneHeight / 2
            for (m in lane.marks) {
                val cx = label + m.x.toInt()
                if (cx < label || cx > width) continue
                g.color = if (m.unexplained) DtaColors.of("caveat") else DtaColors.of("accent")
                if (m.count > 1) {
                    val text = m.count.toString()
                    val w = g.fontMetrics.stringWidth(text) + JBUI.scale(8)
                    g.fillRoundRect(cx - w / 2, cy - r - 2, w, 2 * r + 4, r * 2, r * 2)
                    g.color = DtaColors.of("on-accent")
                    g.drawString(text, cx - w / 2 + JBUI.scale(4), cy + g.fontMetrics.ascent / 2 - 1)
                } else {
                    g.fillOval(cx - r, cy - r, 2 * r, 2 * r)
                }
                if (m.selected) {
                    g.color = DtaColors.of("accent")
                    g.stroke = BasicStroke(JBUI.scale(2).toFloat())
                    g.drawOval(cx - r - 3, cy - r - 3, 2 * r + 6, 2 * r + 6)
                    g.stroke = BasicStroke(1f)
                }
            }
        }
    }

    private fun paintAxis(g: Graphics2D, l: LaneLayout) {
        val y = l.lanes.size * laneHeight
        g.color = DtaColors.of("line")
        g.drawLine(label, y, width, y)
        g.color = JBColor.GRAY
        for (t in l.ticks) {
            val x = label + t.x.toInt()
            g.drawLine(x, y, x, y + JBUI.scale(4))
            g.drawString(t.label, x + 2, y + JBUI.scale(4) + g.fontMetrics.ascent)
        }
    }

    private fun markAt(x: Int, y: Int): LaneLayout.Mark? {
        val l = layout ?: return null
        val lane = l.lanes.getOrNull(y / laneHeight) ?: return null
        return lane.marks.minByOrNull { kotlin.math.abs(label + it.x - x) }?.takeIf { kotlin.math.abs(label + it.x - x) <= JBUI.scale(8) }
    }

    override fun getToolTipText(e: MouseEvent): String? {
        markAt(e.x, e.y)?.let { return it.tooltip }
        val l = layout ?: return null
        val at = positions(l)
        state.chain.lines.firstOrNull { line ->
            val a = at[line.from] ?: return@firstOrNull false
            val b = at[line.to] ?: return@firstOrNull false
            Line2D.ptSegDist(a.first.toDouble(), a.second.toDouble(), b.first.toDouble(), b.second.toDouble(), e.x.toDouble(), e.y.toDouble()) <= JBUI.scale(4)
        }?.let { return it.label }
        return l.spans.firstOrNull { s -> e.x >= label + s.x0 - 3 && e.x <= label + s.x1 + 3 && e.y < l.lanes.size * laneHeight }?.tooltip
    }

    private companion object {
        const val LABEL_PX = 72
        const val LANE_PX = 26
        const val AXIS_PX = 20
        val DASHED = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f, 4f), 0f)

    }
}
