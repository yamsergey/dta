package io.yamsergey.dta.studio.network

import java.awt.event.FocusEvent

/**
 * Feature 063: when the empty query line offers the app's recent filters on focus. Only when the
 * developer put the caret there: a click, or a shortcut or chip that moves it there ([asked]).
 * Focus that arrives by itself (a tab switched, the window activated, focus traversal) offers nothing.
 */
object QueryFocus {
    fun offers(cause: FocusEvent.Cause, asked: Boolean, empty: Boolean): Boolean =
        empty && (asked || cause == FocusEvent.Cause.MOUSE_EVENT)
}
