package io.yamsergey.dta.studio.toolwindow

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * Forms of the same control, widest first: the first that fits the width shows, the others hide;
 * narrower than all, the last one (a body's view modes as buttons, else as a list — 070 mockup B).
 */
class FirstThatFits : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}

    private fun size(parent: Container, width: (List<Component>) -> Int): Dimension {
        val forms = parent.components.toList()
        val ins = parent.insets
        if (forms.isEmpty()) return Dimension(ins.left + ins.right, ins.top + ins.bottom)
        return Dimension(width(forms) + ins.left + ins.right, forms.maxOf { it.preferredSize.height } + ins.top + ins.bottom)
    }

    override fun preferredLayoutSize(parent: Container) = size(parent) { it.first().preferredSize.width }

    override fun minimumLayoutSize(parent: Container) = size(parent) { it.last().preferredSize.width }

    override fun layoutContainer(parent: Container) {
        val forms = parent.components.toList()
        if (forms.isEmpty()) return
        val ins = parent.insets
        val room = parent.width - ins.left - ins.right
        val shown = forms.firstOrNull { it.preferredSize.width <= room } ?: forms.last()
        val height = parent.height - ins.top - ins.bottom
        forms.forEach { c ->
            c.isVisible = c === shown
            if (c === shown) {
                val p = c.preferredSize
                c.setBounds(ins.left, ins.top + ((height - p.height) / 2).coerceAtLeast(0), minOf(p.width, room).coerceAtLeast(0), minOf(p.height, height.coerceAtLeast(p.height)))
            }
        }
    }
}
