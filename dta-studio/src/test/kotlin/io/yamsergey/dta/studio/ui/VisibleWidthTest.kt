package io.yamsergey.dta.studio.ui

import javax.swing.JPanel
import javax.swing.JScrollPane
import kotlin.test.Test
import kotlin.test.assertEquals

/** A row's room is what the viewport shows, not the (wider) component inside it. */
class VisibleWidthTest {
    @Test fun `inside a scroll pane narrower than the component the viewport's width counts`() {
        val wide = JPanel().apply { setSize(341, 300) }
        val scroll = JScrollPane(wide)
        scroll.viewport.setSize(244, 300)
        assertEquals(244, VisibleWidth.of(wide))
    }

    @Test fun `outside a scroll pane the component's own width counts`() {
        assertEquals(120, VisibleWidth.of(JPanel().apply { setSize(120, 10) }))
    }
}
