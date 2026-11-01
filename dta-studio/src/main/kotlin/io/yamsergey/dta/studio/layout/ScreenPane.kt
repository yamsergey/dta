package io.yamsergey.dta.studio.layout

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.Degradations
import io.yamsergey.dta.present.layout.LayoutTree
import io.yamsergey.dta.present.layout.Outline
import io.yamsergey.dta.present.layout.ScreenGeometry
import io.yamsergey.dta.present.layout.ScreenHit
import io.yamsergey.dta.present.layout.outline
import io.yamsergey.dta.protocol.layout.ScreenImageState
import io.yamsergey.dta.studio.ui.DtaColors
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Feature 014 (US2, US3): the screen the tree was captured from, scaled to fit, with the selected
 * element outlined and the one under the pointer highlighted. Clicking picks; everything it draws
 * comes from [LayoutViewState] and the present models (ScreenGeometry, Outline, ScreenPick).
 */
class ScreenPane(private val controller: LayoutController) : JComponent() {
    private var state = LayoutViewState()
    private var decodedFor: LayoutTree? = null
    private var image: BufferedImage? = null

    init {
        isOpaque = true
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val p = geometry()?.toImage(e.x.toDouble(), e.y.toDouble())
                controller.hover(p?.first, p?.second)
            }

            override fun mouseExited(e: MouseEvent) = controller.hover(null)

            override fun mouseClicked(e: MouseEvent) {
                val p = geometry()?.toImage(e.x.toDouble(), e.y.toDouble()) ?: return
                controller.pick(p.first, p.second)
                requestFocusInWindow()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        isFocusable = true
    }

    fun show(s: LayoutViewState) {
        state = s
        if (s.tree !== decodedFor) {
            decodedFor = s.tree
            image = s.tree?.screen?.png?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        }
        toolTipText = hoverText(s)
        repaint()
    }

    /** What the line under the picture says: the hover, else the selection's outline state. */
    fun caption(s: LayoutViewState): String = hoverText(s)
        ?: s.outline?.state?.statement?.let { "Selected element: $it" }
        ?: ""

    private fun hoverText(s: LayoutViewState): String? = when (val hit = s.hover) {
        is ScreenHit.Nodes -> s.tree?.node(hit.paths.first())?.row?.let { r -> listOfNotNull(r.name, r.text).joinToString("  ") }
        null -> null
        else -> hit.statement
    }

    /** Why there is no picture, in the words of the capture. */
    private fun emptyText(): String {
        val tree = state.tree
        val screen = tree?.screen
        return when {
            !state.screenShown -> "The screen pane is off."
            tree == null -> if (state.loading) "Capturing the screen…" else ""
            screen == null -> "This capture has no picture. Refresh to take one."
            screen.image.state == ScreenImageState.BLOCKED -> Degradations.explain("screen-blocked")!!
            screen.image.state == ScreenImageState.NOT_TAKEN -> "No picture: ${screen.image.reason ?: "not taken"}."
            else -> "The picture could not be decoded."
        }
    }

    private fun geometry(): ScreenGeometry? {
        val img = image ?: return null
        if (width <= 0 || height <= 0) return null
        return ScreenGeometry(img.width, img.height, width, height)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            val img = image
            val geo = geometry()
            if (img == null || geo == null) {
                g2.color = JBColor.GRAY
                drawWrapped(g2, emptyText())
                return
            }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(img, geo.originX.toInt(), geo.originY.toInt(), (img.width * geo.scale).toInt(), (img.height * geo.scale).toInt(), null)
            val tree = state.tree ?: return
            when (val hover = state.hover) {
                is ScreenHit.Nodes -> tree.outline(hover.paths.first())?.let { draw(g2, geo, it, DtaColors.of("accent"), 1f, alpha = 128) }
                is ScreenHit.Foreign -> {
                    val b = hover.window.bounds
                    val shape = Outline(io.yamsergey.dta.present.layout.OutlineState.SHOWN, listOf(
                        b.left.toDouble() to b.top.toDouble(), b.right.toDouble() to b.top.toDouble(),
                        b.right.toDouble() to b.bottom.toDouble(), b.left.toDouble() to b.bottom.toDouble(),
                    ))
                    draw(g2, geo, shape, DtaColors.of("caveat"), 1f, alpha = 200, fill = DtaColors.of("caveat-tint"))
                }
                else -> {}
            }
            state.outline?.let { draw(g2, geo, it, DtaColors.of("accent"), 2f, alpha = 255) }
        } finally {
            g2.dispose()
        }
    }

    private fun draw(g2: Graphics2D, geo: ScreenGeometry, outline: Outline, color: java.awt.Color, width: Float, alpha: Int, fill: java.awt.Color? = null) {
        if (outline.corners.isEmpty()) return
        val path = Path2D.Double()
        outline.corners.forEachIndexed { i, (x, y) ->
            val (px, py) = geo.toPane(x, y)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.closePath()
        fill?.let { g2.color = java.awt.Color(it.red, it.green, it.blue, 96); g2.fill(path) }
        g2.color = java.awt.Color(color.red, color.green, color.blue, alpha)
        g2.stroke = BasicStroke(JBUI.scale(width.toInt()).toFloat())
        g2.draw(path)
    }

    private fun drawWrapped(g2: Graphics2D, text: String) {
        if (text.isEmpty()) return
        val metrics = g2.fontMetrics
        val maxWidth = (width - JBUI.scale(32)).coerceAtLeast(JBUI.scale(80))
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (metrics.stringWidth(candidate) > maxWidth && line.isNotEmpty()) { lines += line; line = word } else line = candidate
        }
        if (line.isNotEmpty()) lines += line
        var y = (height - lines.size * metrics.height) / 2 + metrics.ascent
        for (l in lines) {
            g2.drawString(l, (width - metrics.stringWidth(l)) / 2, y)
            y += metrics.height
        }
    }
}
