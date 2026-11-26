package io.yamsergey.dta.studio.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JLabel

/**
 * A text that wraps inside the width its page gives it and never asks for more (D-107: help lines of a settings page
 * ran past the dialog's right edge when they had a width of their own). The page tells its width with [wrapTo]
 * whenever it changes; [html] is the text's HTML (without `<html>`).
 */
class WrappedLabel(html: String = "", private val indent: Int = 0) : JLabel() {
    private var inner = html
    // Before the page says its width the text asks for a modest one: a long line must not make the page wide (it then never wraps).
    private var width = ASKED

    init {
        border = JBUI.Borders.emptyLeft(indent)
        render()
    }

    var html: String
        get() = inner
        set(value) { inner = value; render() }

    /** The text wraps at [available] pixels, this label's indent included. */
    fun wrapTo(available: Int) {
        val w = (available - indent).coerceAtLeast(MIN_WIDTH)
        if (w == width) return
        width = w
        render()
    }

    private fun render() {
        text = if (inner.isEmpty()) "" else if (width > 0) "<html><body style='width: ${cssWidth(width)}px'>$inner</body></html>" else "<html>$inner</html>"
        revalidate()
    }

    /** Swing's HTML reads a CSS px as a point (96/72 pixels): the width that comes out at [pixels] on the screen. */
    private fun cssWidth(pixels: Int) = pixels * 72 / 96

    override fun getPreferredSize(): Dimension = super.getPreferredSize().also { d -> d.width = minOf(d.width, width + indent, ASKED + indent) }
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    private companion object {
        val MIN_WIDTH = JBUI.scale(120)
        /** The width it asks for however wide it wraps: its page gives it more when it has more, and is never made wide by it. */
        val ASKED = JBUI.scale(240)
    }
}
