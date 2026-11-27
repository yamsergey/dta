package io.yamsergey.dta.studio.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-108: the header's connection chip shows its full text or only its dot, never cut text; the full state is in the tooltip. */
class ConnectionChipFitTest {
    private val full = "● connected"
    // Widths in px: the chip's full text 84, the dot 10; the bar's other parts 150; the combos need at least 160.
    private fun fit(available: Int, text: String = full, tip: String? = null) =
        ConnectionChipFit.of(text, tip, fullWidth = 84, dotWidth = 10, others = 150, combosMin = 160, available = available)

    @Test
    fun `room for the combos and the whole text shows the text`() {
        val s = fit(available = 150 + 160 + 84)
        assertEquals(full, s.text)
        assertEquals(full, s.tooltip)
    }

    @Test
    fun `less room shows only the dot, with the state in the tooltip - 900 and 420 px alike`() {
        for (available in listOf(150 + 160 + 83, 400, 300, 0)) {
            val s = fit(available)
            assertEquals("●", s.text, "available $available")
            assertEquals("● connected", s.tooltip)
        }
    }

    @Test
    fun `the tooltip keeps the reason`() {
        assertEquals("● lost: no ping", fit(100, "● lost", "no ping").tooltip)
        assertEquals("● lost: no ping", fit(1000, "● lost", "no ping").tooltip)
    }

    @Test
    fun `no state, nothing to show`() {
        val s = ConnectionChipFit.of("", null, 0, 0, 150, 160, 100)
        assertEquals("", s.text)
        assertTrue(s.tooltip == null)
    }
}
