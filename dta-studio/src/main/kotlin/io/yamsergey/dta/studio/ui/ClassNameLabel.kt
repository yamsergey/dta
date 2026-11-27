package io.yamsergey.dta.studio.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import javax.swing.JLabel

/**
 * Draws a class name by the truncation rule ([ClassNameFit]): the package muted, the class in the text colour, fitted
 * to [room] px. [text] is what is drawn (so a test can read it); [fullName] is for the tooltip. Reusable as a cell or
 * a row: set [show] with the name and the width available, then paint.
 */
class ClassNameLabel : JLabel() {
    var fullName: String = ""
        private set
    var fit: ClassNameFit.Fit = ClassNameFit.Fit("", "")
        private set
    private var muted: java.awt.Color = JBColor.GRAY
    private var strong: java.awt.Color = JBColor.foreground()
    private var fill: java.awt.Color? = null

    fun show(name: String, room: Int, selected: Boolean = false, background: java.awt.Color? = null) {
        fullName = name
        val metrics = getFontMetrics(font)
        fit = ClassNameFit.fit(name, { metrics.stringWidth(it) }, (room - insets.left - insets.right).coerceAtLeast(0))
        // Last resort, when even the whole simple name does not fit: an ellipsis inside it, never a clip without one.
        val room2 = (room - insets.left - insets.right).coerceAtLeast(0)
        if (metrics.stringWidth(fit.text) > room2) {
            fit = ClassNameFit.Fit("", ClassNameFit.end(fit.text, { metrics.stringWidth(it) }, room2))
        }
        text = fit.text
        toolTipText = name
        strong = com.intellij.util.ui.UIUtil.getTableForeground(selected, selected)
        muted = if (selected) strong else JBColor.GRAY
        fill = background
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        fill?.let { g.color = it; g.fillRect(0, 0, width, height) }
        g.font = font
        val m = g.fontMetrics
        val y = (height - m.height) / 2 + m.ascent
        var x = insets.left
        g.color = muted; g.drawString(fit.prefix, x, y)
        x += m.stringWidth(fit.prefix)
        g.color = strong; g.drawString(fit.name, x, y)
    }

    companion object { val gap get() = JBUI.scale(10) }
}
