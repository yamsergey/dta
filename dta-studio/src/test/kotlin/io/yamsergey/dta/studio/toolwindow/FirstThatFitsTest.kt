package io.yamsergey.dta.studio.toolwindow

import java.awt.Dimension
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstThatFitsTest {
    private fun box(w: Int, h: Int) = JPanel().apply { preferredSize = Dimension(w, h) }

    @Test
    fun `the wide form shows while it fits`() {
        val wide = box(300, 24); val narrow = box(150, 26)
        val p = JPanel(FirstThatFits()).apply { add(wide); add(narrow) }
        p.size = Dimension(320, 30); p.doLayout()
        assertTrue(wide.isVisible); assertFalse(narrow.isVisible)
        assertEquals(300, wide.width)
    }

    @Test
    fun `the narrow form shows when the wide one does not fit`() {
        val wide = box(300, 24); val narrow = box(150, 26)
        val p = JPanel(FirstThatFits()).apply { add(wide); add(narrow) }
        p.size = Dimension(265, 30); p.doLayout()
        assertFalse(wide.isVisible); assertTrue(narrow.isVisible)
        assertEquals(0, narrow.x); assertEquals(150, narrow.width)
    }

    @Test
    fun `it asks for the wide form's width and the taller height, and can shrink to the narrow one`() {
        val p = JPanel(FirstThatFits()).apply { add(box(300, 24)); add(box(150, 26)) }
        assertEquals(Dimension(300, 26), p.preferredSize)
        assertEquals(150, p.minimumSize.width)
    }

    @Test
    fun `narrower than every form, the last one shows`() {
        val wide = box(300, 24); val narrow = box(150, 26)
        val p = JPanel(FirstThatFits()).apply { add(wide); add(narrow) }
        p.size = Dimension(100, 30); p.doLayout()
        assertTrue(narrow.isVisible); assertEquals(100, narrow.width)
    }
}
