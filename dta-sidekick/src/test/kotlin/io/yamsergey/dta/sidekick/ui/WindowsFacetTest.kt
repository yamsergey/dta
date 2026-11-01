package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 016 (T008): the `ui.windows` facet — the app's open windows, bottom first. */
class WindowsFacetTest {
    private val activity = WindowRef("w1", WindowKind.ACTIVITY, title = "MainActivity", owner = "com.example.shop.MainActivity")
    private val dialog = WindowRef("w2", WindowKind.DIALOG, title = "Delete item?", owner = "com.example.shop.MainActivity")
    private val popup = WindowRef("w3", WindowKind.POPUP, title = null)

    @Test
    fun `activities by name, other windows by kind and title, in z-order`() {
        assertEquals("MainActivity + dialog \"Delete item?\" + popup", WindowsFacet.value(listOf(activity, dialog, popup)))
    }

    @Test
    fun `no windows is no value`() {
        assertNull(WindowsFacet.value(emptyList()))
    }

    @Test
    fun `the value is bounded`() {
        val many = (1..20).map { dialog.copy(windowId = "w$it", title = "A rather long dialog title number $it") }
        val value = WindowsFacet.value(many)!!
        assertTrue(value.length <= Bounds.FACET_VALUE, value)
        assertTrue(value.endsWith("…"), value)
    }
}
