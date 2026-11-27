package io.yamsergey.dta.studio.ui

import java.awt.Component
import javax.swing.JViewport
import javax.swing.SwingUtilities

/**
 * The width a row can really use: inside a scroll pane the viewport's, when it is narrower than the component. A renderer
 * that fits its text to the component's own width paints past the pane's edge and is clipped without an ellipsis.
 * Reads sizes only: safe inside a renderer (it never asks a tree or table for row geometry).
 */
object VisibleWidth {
    fun of(c: Component): Int {
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport ?: return c.width
        return if (viewport.width in 1 until c.width) viewport.width else c.width
    }
}
