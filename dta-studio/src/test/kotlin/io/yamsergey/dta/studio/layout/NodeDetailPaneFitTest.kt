package io.yamsergey.dta.studio.layout

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.present.layout.NodeDetail
import java.awt.Component
import java.awt.Container
import javax.swing.JTextArea

/** D-144: nothing in the detail pane is hard-cut: the empty hint wraps, property values end in an ellipsis with the whole in a tooltip. */
class NodeDetailPaneFitTest : BasePlatformTestCase() {
    private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap { all(it) } ?: emptyList())

    private fun pane(width: Int): NodeDetailPane {
        val p = NodeDetailPane({}, { _, _, _ -> })
        p.setSize(width, 400)
        return p
    }

    private fun lay(c: Container) { c.doLayout(); c.components.filterIsInstance<Container>().forEach { lay(it) } }

    fun `test the empty hint wraps instead of being cut`() {
        val p = pane(160)
        lay(p)
        val hint = all(p).filterIsInstance<JTextArea>().firstOrNull { it.text == "Select a node to see its properties." }
        assertNotNull("the hint is a wrapping text", hint)
        assertTrue(hint!!.lineWrap)
    }

    fun `test a long class and bounds value end in an ellipsis with the whole in a tooltip`() {
        val p = pane(220)
        val cls = "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat"
        p.show(NodeDetail("t", listOf("a"), listOf("Class" to cls, "Bounds" to "[0,0][1080,2400] (1080×2400 px)"), null, emptyList(), emptyList(), emptyList()), null)
        lay(p)
        val labels = all(p).filterIsInstance<io.yamsergey.dta.studio.ui.FitLabel>()
        assertEquals(2, labels.size)
        assertTrue(labels.any { it.toolTipText == cls })
        assertTrue(labels.all { it.minimumSize.width == 0 })
    }
}
