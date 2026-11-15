package io.yamsergey.dta.studio.network

import java.awt.event.FocusEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The empty query line offers filters only when the developer put the caret there (063, found 2026-11-15). */
class QueryFocusTest {
    @Test
    fun `a click into the empty line offers the filters`() {
        assertTrue(QueryFocus.offers(FocusEvent.Cause.MOUSE_EVENT, asked = false, empty = true))
    }

    @Test
    fun `a shortcut or a chip that moves the caret there offers them`() {
        assertTrue(QueryFocus.offers(FocusEvent.Cause.UNKNOWN, asked = true, empty = true))
    }

    @Test
    fun `focus that arrives by itself offers nothing`() {
        for (cause in listOf(FocusEvent.Cause.ACTIVATION, FocusEvent.Cause.UNKNOWN, FocusEvent.Cause.TRAVERSAL, FocusEvent.Cause.TRAVERSAL_FORWARD, FocusEvent.Cause.CLEAR_GLOBAL_FOCUS_OWNER)) {
            assertFalse(QueryFocus.offers(cause, asked = false, empty = true), cause.name)
        }
    }

    @Test
    fun `a line with text offers nothing on focus`() {
        assertFalse(QueryFocus.offers(FocusEvent.Cause.MOUSE_EVENT, asked = true, empty = false))
    }
}
