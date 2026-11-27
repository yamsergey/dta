package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.JBLabel
import java.awt.Dimension

/**
 * A one-line label that asks for no width of its own, so its pane sizes it: it ends in an ellipsis when short of room
 * and the whole text is its tooltip (truncation rule: nothing hard-cut, nothing cut without the full text on hover).
 * With [asClass] the text is a class name: the simple name stays whole and the package shrinks first ([ClassNameFit]).
 */
class FitLabel(private val asClass: Boolean = false, private val asResource: Boolean = false) : JBLabel() {
    private var full: String = ""

    fun show(text: String) {
        full = text
        toolTipText = text.takeIf { it.isNotBlank() }
        super.setText(text)
        refit()
    }

    override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        refit()
    }

    private fun refit() {
        if (!(asClass || asResource) || full.isEmpty() || width <= 0) return
        val metrics = getFontMetrics(font)
        val room = (width - insets.left - insets.right).coerceAtLeast(0)
        val fit = if (asResource) ClassNameFit.resource(full, { metrics.stringWidth(it) }, room) else ClassNameFit.fit(full, { metrics.stringWidth(it) }, room).text
        if (fit != text) super.setText(fit)
    }
}
