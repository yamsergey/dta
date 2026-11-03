package io.yamsergey.dta.studio.toolwindow

import com.intellij.ui.components.JBLabel
import java.awt.Dimension

/** The widest width seen since the last [reset] (feature 024). */
class SteadyWidth {
    private var widest = 0

    fun of(natural: Int): Int {
        widest = maxOf(widest, natural)
        return widest
    }

    fun reset() {
        widest = 0
    }
}

/**
 * A label that never shrinks until [reset]: a counter like "21000 of 21011 shown" changes width
 * with its digits in a proportional font, which moved every control to its right on each request.
 */
class SteadyLabel : JBLabel() {
    private val steady = SteadyWidth()

    override fun getPreferredSize(): Dimension = super.getPreferredSize().let { Dimension(steady.of(it.width), it.height) }

    override fun getMinimumSize(): Dimension = preferredSize

    fun reset() {
        steady.reset()
        revalidate()
    }
}
