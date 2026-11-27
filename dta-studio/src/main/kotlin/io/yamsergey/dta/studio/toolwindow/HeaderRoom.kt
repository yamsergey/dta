package io.yamsergey.dta.studio.toolwindow

import java.awt.Component

/** D-108: how much room the title bar really leaves a component placed in it. */
object HeaderRoom {
    /** Width from [bar]'s left edge to the first part to its right in the header; null when nothing is found. */
    fun available(bar: Component): Int? = null
}
