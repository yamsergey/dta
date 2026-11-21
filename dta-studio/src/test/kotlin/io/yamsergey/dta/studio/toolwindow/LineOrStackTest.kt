package io.yamsergey.dta.studio.toolwindow

import java.awt.Dimension
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineOrStackTest {
    private fun box(w: Int, h: Int) = JPanel().apply { preferredSize = Dimension(w, h); minimumSize = preferredSize }

    private fun row(vararg parts: JPanel) = JPanel(LineOrStack(gap = 10)).apply { parts.forEach { add(it) } }

    @Test
    fun `parts that fit stand in one line, the last at the right edge`() {
        val left = box(200, 20); val right = box(100, 24)
        val p = row(left, right)
        p.size = Dimension(400, 30); p.doLayout()
        assertEquals(0, left.x); assertEquals(200, left.width)
        assertEquals(300, right.x, "the last part ends at the right edge"); assertEquals(100, right.width)
        assertEquals(24, p.preferredSize.height)
    }

    @Test
    fun `parts that do not fit stand one under another, none overlapping or squeezed`() {
        val left = box(300, 20); val right = box(200, 24)
        val p = row(left, right)
        p.size = Dimension(400, 60); p.doLayout()
        assertEquals(0, left.y); assertEquals(300, left.width, "not squeezed")
        assertTrue(right.y >= left.y + left.height, "under the first, not over it: ${right.y}")
        assertEquals(0, right.x)
        assertEquals(44, p.preferredSize.height, "both lines tall")
    }

    @Test
    fun `hidden parts take no room`() {
        val left = box(300, 20); val right = box(200, 24).apply { isVisible = false }
        val p = row(left, right)
        p.size = Dimension(400, 60); p.doLayout()
        assertEquals(20, p.preferredSize.height)
    }

    @Test
    fun `a part wider than the row gets the row's width`() {
        val wide = box(600, 20)
        val p = row(wide)
        p.size = Dimension(400, 20); p.doLayout()
        assertEquals(400, wide.width)
    }
}
