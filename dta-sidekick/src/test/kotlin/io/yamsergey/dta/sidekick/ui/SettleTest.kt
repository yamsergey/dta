package io.yamsergey.dta.sidekick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 015 (T009, research R4): quiet for 250 ms, or 1 s after a burst began. */
class SettleTest {
    @Test
    fun `quiet after the last draw`() {
        val s = Settle()
        s.added(0)
        s.drew(100)
        assertEquals(350, s.dueAt())
        assertNull(s.fire(300))
        val fired = s.fire(350)!!
        assertFalse(fired.byCap)
        assertNull(s.dueAt())
    }

    @Test
    fun `continuous drawing settles at the cap`() {
        val s = Settle()
        s.added(0)
        for (t in 0L..1000L step 16) s.drew(t)
        assertEquals(1000, s.dueAt())
        assertTrue(s.fire(1000)!!.byCap)
        assertEquals(1, s.capsInARow)
        s.drew(1010)
        for (t in 1010L..2010L step 16) s.drew(t)
        assertTrue(s.fire(2010)!!.byCap)
        assertEquals(2, s.capsInARow)
        s.drew(2100)
        assertFalse(s.fire(2350)!!.byCap)
        assertEquals(0, s.capsInARow)
    }

    @Test
    fun `a draw after settling starts a new burst`() {
        val s = Settle()
        s.added(0)
        s.fire(250)
        s.drew(5000)
        assertEquals(5250, s.dueAt())
    }
}
