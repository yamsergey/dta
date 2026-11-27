package io.yamsergey.dta.studio.toolwindow

import java.awt.Dimension
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertTrue

/** D-140: the Copy command link stays beside the agent choice at 420 px; only the hint goes below. */
class ConnectRowTest {
    private fun box(w: Int, h: Int) = JPanel().apply { preferredSize = Dimension(w, h); minimumSize = preferredSize }

    @Test
    fun `the link is beside the choice when the hint does not fit`() {
        val agent = box(217, 34); val copy = box(99, 17); val hint = box(300, 14)
        val p = ConnectRow.build(agent, copy, hint)
        p.size = Dimension(354, 80); p.doLayout()
        val pair = agent.parent
        pair.doLayout()
        assertTrue(copy.y < agent.y + agent.height && copy.y + copy.height > agent.y, "link y ${copy.y} not in the choice's ${agent.y}..${agent.y + agent.height}")
        assertTrue(copy.x >= agent.x + agent.width, "link right of the choice")
        assertTrue(hint.y >= pair.y + pair.height, "hint under the pair")
    }
}
