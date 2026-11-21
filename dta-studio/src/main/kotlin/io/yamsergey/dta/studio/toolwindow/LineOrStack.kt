package io.yamsergey.dta.studio.toolwindow

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.SwingUtilities

/**
 * A row of parts: in one line when they fit (the first at the left, the last at the right edge),
 * otherwise one under another at full size — never overlapping or squeezed, as the tab is at any
 * width (a caption beside "N new below", a body's title beside its view modes).
 */
class LineOrStack(private val gap: Int) : LayoutManager {
    private var stackedWhenMeasured: Boolean? = null

    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}

    private fun parts(parent: Container) = parent.components.filter { it.isVisible }

    private fun lineWidth(parts: List<Component>) = parts.sumOf { it.preferredSize.width } + gap * (parts.size - 1).coerceAtLeast(0)

    private fun room(parent: Container) = parent.width - parent.insets.left - parent.insets.right

    private fun stacked(parent: Container, parts: List<Component>) = parts.size > 1 && parent.width > 0 && lineWidth(parts) > room(parent)

    override fun preferredLayoutSize(parent: Container): Dimension {
        val parts = parts(parent)
        val ins = parent.insets
        val stacked = stacked(parent, parts).also { stackedWhenMeasured = it }
        val inner = if (stacked) Dimension(parts.maxOfOrNull { it.preferredSize.width } ?: 0, parts.sumOf { it.preferredSize.height })
        else Dimension(lineWidth(parts), parts.maxOfOrNull { it.preferredSize.height } ?: 0)
        return Dimension(inner.width + ins.left + ins.right, inner.height + ins.top + ins.bottom)
    }

    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent).let { Dimension(0, it.height) }

    override fun layoutContainer(parent: Container) {
        val parts = parts(parent)
        val ins = parent.insets
        val room = room(parent)
        val stacked = stacked(parent, parts)
        // Its height was measured for the other arrangement: measured again at this width.
        if (stackedWhenMeasured != null && stackedWhenMeasured != stacked) SwingUtilities.invokeLater { parent.revalidate() }
        if (stacked) {
            var y = ins.top
            parts.forEach { c -> val p = c.preferredSize; c.setBounds(ins.left, y, minOf(p.width, room), p.height); y += p.height }
            return
        }
        val height = parent.height - ins.top - ins.bottom
        var x = ins.left
        parts.forEachIndexed { i, c ->
            val p = c.preferredSize
            val w = minOf(p.width, room - (x - ins.left)).coerceAtLeast(0)
            val left = if (i == parts.lastIndex && i > 0) ins.left + room - w else x
            c.setBounds(left, ins.top + ((height - p.height) / 2).coerceAtLeast(0), w, p.height)
            x += w + gap
        }
    }
}
