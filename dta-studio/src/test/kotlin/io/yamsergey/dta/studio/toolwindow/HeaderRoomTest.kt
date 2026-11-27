package io.yamsergey.dta.studio.toolwindow

import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D-108: the context bar's room is what the header leaves before its right-hand actions (gear, checkbox), not a guessed constant. */
class HeaderRoomTest {
    private fun panel(x: Int, w: Int) = JPanel(null).apply { bounds = Rectangle(x, 0, w, 20) }

    /** header(0..W): [mascot area: tabActions(x=30, w=700 laid out wider than visible): bar(x=40 inside)] [right actions at rightX]. */
    private fun header(rightX: Int): Pair<JComponent, JComponent> {
        val header = JPanel(null).apply { bounds = Rectangle(0, 0, 900, 20) }
        val tabActions = panel(30, 700)
        val bar = panel(40, 600)
        tabActions.add(bar)
        header.add(tabActions)
        header.add(panel(rightX, 900 - rightX))
        return header to bar
    }

    @Test
    fun `the room ends where the right-hand actions begin, wherever the bar was laid out to`() {
        for (rightX in listOf(780, 560, 330)) {
            val (_, bar) = header(rightX)
            assertEquals(rightX - (30 + 40), HeaderRoom.available(bar), "actions at $rightX")
        }
    }

    @Test
    fun `no neighbour to the right, no answer`() {
        val lone = panel(0, 300).also { it.add(panel(10, 100)) }
        assertNull(HeaderRoom.available(lone.getComponent(0)))
    }
}
