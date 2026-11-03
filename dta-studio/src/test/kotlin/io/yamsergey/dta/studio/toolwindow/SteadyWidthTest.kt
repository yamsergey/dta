package io.yamsergey.dta.studio.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 024: the counter caption never shrinks while traffic arrives, so the toolbar stays put. */
class SteadyWidthTest {
    @Test
    fun `width grows with the text and never shrinks until reset`() {
        val steady = SteadyWidth()
        assertEquals(120, steady.of(120))
        assertEquals(131, steady.of(131))
        assertEquals(131, steady.of(126), "21011 is narrower than 21000 in a proportional font: no shrink")
        steady.reset()
        assertEquals(90, steady.of(90))
    }
}
