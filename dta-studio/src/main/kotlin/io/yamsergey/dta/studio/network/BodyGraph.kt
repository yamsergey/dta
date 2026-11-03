package io.yamsergey.dta.studio.network

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.network.DataGraph
import io.yamsergey.dta.present.network.DataNode
import io.yamsergey.dta.present.network.GraphCard
import io.yamsergey.dta.present.network.GraphLayout
import io.yamsergey.dta.present.network.GraphRow
import io.yamsergey.dta.present.network.GraphStyle
import io.yamsergey.dta.present.network.NodeKind
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min

/**
 * The body as a graph of cards, left to right (feature 022, US3): a card per shown object, array
 * or element, an arrow from a row to its nested value's card. Click a nested row to expand or
 * collapse it, "+N more" to show all rows; drag to pan, Ctrl/Cmd+wheel to zoom, Fit. The layout
 * is [GraphLayout]'s; this class only draws it. [onSelect] hears the selected row's path.
 */
class BodyGraph(private val root: DataNode, private val onSelect: (String) -> Unit) : JPanel(BorderLayout()) {
    private val style = GraphStyle(
        titleHeight = JBUI.scale(24), rowHeight = JBUI.scale(20), padding = JBUI.scale(8), gapX = JBUI.scale(56), gapY = JBUI.scale(16),
        minWidth = JBUI.scale(96), maxWidth = JBUI.scale(360),
    )
    private val font: Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).let { it.deriveFont(JBUI.scaleFontSize(it.size2D.coerceAtMost(13f)).toFloat()) }
    private val bold = font.deriveFont(Font.BOLD)
    private val parents = HashMap<String, String>()
    private var expanded: Set<String> = GraphLayout.defaultExpanded(root)
    private var graph: DataGraph = DataGraph(emptyList(), emptyList(), 0, 0)
    private var selected: String? = null
    private val note = JBLabel().apply { foreground = JBColor.GRAY }
    private val canvas = Canvas()

    init {
        fun index(n: DataNode) { n.children.forEach { parents[it.path] = n.path; index(it) } }
        index(root)
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(ActionLink("Fit") { canvas.fit() })
            add(ActionLink("+") { canvas.zoomBy(1.25, null) })
            add(ActionLink("−") { canvas.zoomBy(0.8, null) })
            add(ActionLink("Expand all") {
                val all = GraphLayout.expandAll(root)
                expanded = all.paths
                relayout(if (all.truncated) "Expanded the first ${GraphLayout.MAX_CARDS} cards; click a collapsed row to open more." else null)
                canvas.fit()
            })
            add(ActionLink("Collapse all") { expanded = emptySet(); relayout(null); canvas.fit() })
            add(note)
        }
        add(bar, BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
        relayout(null)
    }

    /** Shows the node at [path] (expanding what hides it) as selected, without telling [onSelect]. */
    fun select(path: String) {
        var p: String? = parents[path]
        val open = HashSet(expanded)
        // A card for the path itself when it is a container, and for every ancestor under the root.
        val node = find(root, path)
        if (node?.container == true && path != root.path) open += path
        while (p != null && p != root.path) { open += p; p = parents[p] }
        if (open != expanded) { expanded = open; relayout(null) }
        selected = path
        canvas.reveal(path)
        canvas.repaint()
    }

    private fun find(n: DataNode, path: String): DataNode? =
        if (n.path == path) n else n.children.firstOrNull { path == it.path || (path.startsWith(it.path) && path[it.path.length] in "./[") }?.let { find(it, path) }

    private fun relayout(message: String?) {
        val metrics = canvas.getFontMetrics(font)
        graph = GraphLayout.layout(root, expanded, { metrics.stringWidth(it) }, style)
        note.text = message ?: "${graph.cards.size} cards · click a row with { } [ ] < > to open or close it"
        canvas.repaint()
    }

    private inner class Canvas : JComponent() {
        private var scale = 1.0
        private var dx = JBUI.scale(16).toDouble()
        private var dy = JBUI.scale(16).toDouble()
        private var drag: Point2D.Double? = null
        private var fitted = false

        init {
            isOpaque = true
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { drag = Point2D.Double(e.x.toDouble(), e.y.toDouble()); requestFocusInWindow() }
                override fun mouseReleased(e: MouseEvent) { drag = null; cursor = Cursor.getDefaultCursor() }
                override fun mouseDragged(e: MouseEvent) {
                    val d = drag ?: return
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    dx += e.x - d.x; dy += e.y - d.y
                    drag = Point2D.Double(e.x.toDouble(), e.y.toDouble())
                    repaint()
                }
                override fun mouseClicked(e: MouseEvent) { click(e) }
                override fun mouseMoved(e: MouseEvent) {
                    val hit = hit(e)
                    cursor = if (hit != null && (hit.second.container || hit.second.more > 0)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                    toolTipText = hit?.second?.let { if (it.more > 0) "Show all rows" else it.path }
                }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    if (e.isControlDown || e.isMetaDown) zoomBy(if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1, Point2D.Double(e.x.toDouble(), e.y.toDouble()))
                    else {
                        val step = e.preciseWheelRotation * JBUI.scale(30)
                        if (e.isShiftDown) dx -= step else dy -= step
                        repaint()
                    }
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener(mouse)
        }

        fun zoomBy(factor: Double, at: Point2D.Double?) {
            val p = at ?: Point2D.Double(width / 2.0, height / 2.0)
            val next = (scale * factor).coerceIn(0.1, 3.0)
            // Keep the point under the cursor where it is.
            dx = p.x - (p.x - dx) * next / scale
            dy = p.y - (p.y - dy) * next / scale
            scale = next
            repaint()
        }

        fun fit() {
            if (width == 0 || graph.cards.isEmpty()) return
            val margin = JBUI.scale(16)
            scale = min(1.0, min((width - 2.0 * margin) / max(1, graph.width), (height - 2.0 * margin) / max(1, graph.height))).coerceAtLeast(0.1)
            dx = margin.toDouble(); dy = margin.toDouble()
            if (graph.height * scale < height - 2 * margin) dy = (height - graph.height * scale) / 2
            repaint()
        }

        fun reveal(path: String) {
            val card = graph.cards.firstOrNull { c -> c.path == path || c.rows.any { it.path == path } } ?: return
            val cx = card.x + card.width / 2.0
            val cy = card.y + card.height / 2.0
            if (width == 0) return
            val sx = dx + cx * scale
            val sy = dy + cy * scale
            if (sx !in 0.0..width.toDouble() || sy !in 0.0..height.toDouble()) {
                dx = width / 2.0 - cx * scale
                dy = height / 2.0 - cy * scale
            }
        }

        private fun hit(e: MouseEvent): Pair<GraphCard, GraphRow>? {
            val x = (e.x - dx) / scale
            val y = (e.y - dy) / scale
            val card = graph.cards.firstOrNull { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height } ?: return null
            val i = ((y - card.y - style.titleHeight) / style.rowHeight).toInt()
            if (y < card.y + style.titleHeight || i !in card.rows.indices) return null
            return card to card.rows[i]
        }

        private fun click(e: MouseEvent) {
            val (_, row) = hit(e) ?: return
            when {
                row.more > 0 -> { expanded = GraphLayout.toggle(expanded, GraphLayout.moreKey(row.path)); relayout(null) }
                row.container -> { expanded = GraphLayout.toggle(expanded, row.path); relayout(null) }
            }
            if (row.more == 0) { selected = row.path; onSelect(row.path) }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            if (!fitted && width > 0) { fitted = true; if (graph.width * 1.0 > width || graph.height > height) fit() }
            val g2 = g.create() as Graphics2D
            try {
                g2.color = EditorColorsManager.getInstance().globalScheme.defaultBackground
                g2.fillRect(0, 0, width, height)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                UIUtil.applyRenderingHints(g2)
                g2.translate(dx, dy)
                g2.scale(scale, scale)
                paintEdges(g2)
                graph.cards.forEach { paintCard(g2, it) }
            } finally {
                g2.dispose()
            }
        }

        private fun paintEdges(g: Graphics2D) {
            g.color = JBColor.namedColor("Component.borderColor", JBColor.GRAY)
            g.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.2f)
            graph.edges.forEach { edge ->
                val from = graph.card(edge.from) ?: return@forEach
                val to = graph.card(edge.to) ?: return@forEach
                val x1 = (from.x + from.width).toDouble()
                val y1 = from.y + style.titleHeight + edge.row * style.rowHeight + style.rowHeight / 2.0
                val x2 = to.x.toDouble()
                val y2 = to.y + style.titleHeight / 2.0
                val mid = (x1 + x2) / 2
                g.draw(CubicCurve2D.Double(x1, y1, mid, y1, mid, y2, x2, y2))
                g.fillOval(x1.toInt() - 3, y1.toInt() - 3, 6, 6)
            }
        }

        private fun paintCard(g: Graphics2D, c: GraphCard) {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val arc = JBUI.scale(10).toDouble()
            val shape = RoundRectangle2D.Double(c.x.toDouble(), c.y.toDouble(), c.width.toDouble(), c.height.toDouble(), arc, arc)
            g.color = JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
            g.fill(shape)
            // The title band says what the card is.
            val accent = color(if (c.kind == NodeKind.ELEMENT) DefaultLanguageHighlighterColors.MARKUP_TAG else DefaultLanguageHighlighterColors.INSTANCE_FIELD)
            val oldClip = g.clip
            g.clip(shape)
            g.color = blend(accent, scheme.defaultBackground, 0.18)
            g.fillRect(c.x, c.y, c.width, style.titleHeight)
            if (c.rows.any { it.path == selected } || c.path == selected) {
                c.rows.indexOfFirst { it.path == selected }.takeIf { it >= 0 }?.let { i ->
                    g.color = UIUtil.getListSelectionBackground(false)
                    g.fillRect(c.x, c.y + style.titleHeight + i * style.rowHeight, c.width, style.rowHeight)
                }
            }
            val text = c.x + style.padding
            val fm = g.getFontMetrics(font)
            val baseline = (style.rowHeight + fm.ascent - fm.descent) / 2
            g.font = bold
            g.color = accent
            val kindLabel = when (c.kind) { NodeKind.ARRAY -> "[ ]"; NodeKind.OBJECT -> "{ }"; else -> "" }
            g.drawString(clip("${c.title}  $kindLabel".trim(), c.width - 2 * style.padding, g.getFontMetrics(bold)), text, c.y + (style.titleHeight + fm.ascent - fm.descent) / 2)
            g.font = font
            c.rows.forEachIndexed { i, row ->
                val top = c.y + style.titleHeight + i * style.rowHeight
                var x = text
                val room = c.width - 2 * style.padding
                if (row.more > 0) {
                    g.color = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
                    g.drawString(row.text, x, top + baseline)
                    return@forEachIndexed
                }
                val key = if (row.key.startsWith("[")) row.key else "${row.key}:"
                g.color = color(if (row.kind == NodeKind.ATTRIBUTE || row.kind == NodeKind.ELEMENT && !row.container) DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE else DefaultLanguageHighlighterColors.INSTANCE_FIELD)
                g.drawString(clip(key, room, fm), x, top + baseline)
                x += fm.stringWidth("$key ")
                val value = if (row.container) "${if (row.expanded) "▾" else "▸"} ${row.text}" else row.text
                g.color = if (row.container) JBColor.GRAY else color(BodyTree.valueKey(row.kind))
                g.drawString(clip(value, room - (x - text), fm), x, top + baseline)
            }
            g.clip = oldClip
            g.color = JBColor.namedColor("Component.borderColor", JBColor.GRAY)
            g.stroke = BasicStroke(1f)
            g.draw(shape)
        }

        private fun clip(s: String, room: Int, fm: java.awt.FontMetrics): String {
            if (room <= 0) return ""
            if (fm.stringWidth(s) <= room) return s
            var end = s.length
            while (end > 0 && fm.stringWidth(s.substring(0, end) + "…") > room) end--
            return s.substring(0, end) + "…"
        }

        private fun color(key: com.intellij.openapi.editor.colors.TextAttributesKey): Color =
            EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.foregroundColor ?: JBColor.foreground()

        private fun blend(a: Color, b: Color, t: Double) = Color(
            (a.red * t + b.red * (1 - t)).toInt(), (a.green * t + b.green * (1 - t)).toInt(), (a.blue * t + b.blue * (1 - t)).toInt(),
        )
    }
}
