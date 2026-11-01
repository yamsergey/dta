package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.SummaryElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Feature 016 (T031): the screen's structure without its data. */
class ScreenFingerprintTest {
    private fun cart(vararg items: String, empty: Boolean = false) = ContentSummary(
        buildList {
            add(SummaryElement("heading", text = "Cart", name = "cart_title", depth = 1))
            if (empty) add(SummaryElement("text", text = "Your cart is empty", name = "cart_empty", depth = 2))
            items.forEach { add(SummaryElement("text", text = it, name = "cart_item", depth = 2)) }
            add(SummaryElement("button", text = "Delete", name = "delete_button", depth = 1))
        },
    )

    private fun of(s: ContentSummary) = ScreenFingerprint.of("com.example.shop.MainActivity", s)

    @Test
    fun `lists of three and of two items are the same screen`() {
        assertEquals(of(cart("Shoes", "Socks", "Hat")).value, of(cart("Shoes", "Socks")).value)
    }

    @Test
    fun `a list and its empty state are different screens`() {
        assertNotEquals(of(cart("Shoes")).value, of(cart(empty = true)).value)
    }

    @Test
    fun `texts, checked state and enabled state do not count`() {
        val a = cart("Shoes")
        val b = a.copy(elements = a.elements.map { it.copy(text = "other", checked = true, enabled = false) })
        assertEquals(of(a).value, of(b).value)
    }

    @Test
    fun `progress does not count`() {
        val a = cart("Shoes")
        val b = a.copy(elements = a.elements + SummaryElement("progress", depth = 1))
        assertEquals(of(a).value, of(b).value)
    }

    @Test
    fun `repeated rows with inner structure collapse too`() {
        fun rows(n: Int) = ContentSummary((1..n).flatMap { listOf(SummaryElement("button", name = "row", depth = 1), SummaryElement("text", name = "row_title", depth = 2)) })
        assertEquals(of(rows(2)).value, of(rows(7)).value)
    }

    @Test
    fun `the value names the owner, and a cut summary says so`() {
        val f = of(cart("Shoes"))
        assertTrue(Regex("MainActivity·[0-9a-f]{8}").matches(f.value), f.value)
        assertFalse(f.truncated)
        assertTrue(of(cart("Shoes").copy(omitted = 3)).truncated)
    }
}
