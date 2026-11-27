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

    /** V2: the selected element (the last item) is whole, the middle ones are one "…", the whole path is a tooltip. */
    fun `test the breadcrumb keeps the selected element whole and folds the middle`() {
        val p = pane(260)
        val path = listOf("DecorView", "ActionBarOverlayLayout", "FrameLayout", "LinearLayout", "TextView")
        p.show(NodeDetail("t", path, listOf("Kind" to "view"), null, emptyList(), emptyList(), emptyList()), null)
        lay(p)
        val row = all(p).filterIsInstance<io.yamsergey.dta.studio.ui.BreadcrumbRow>().single()
        lay(row)
        val last = row.items.last()
        assertTrue("selected visible", last.isVisible)
        assertEquals("TextView", (last as javax.swing.JLabel).text)
        assertTrue("selected whole: ${last.width} of ${last.preferredSize.width}", last.width >= last.preferredSize.width)
        assertTrue("the middle is folded", row.ellipsis.isVisible)
        assertTrue("nothing past the edge", row.items.filter { it.isVisible }.all { it.x + it.width <= row.width })
        assertEquals(path.joinToString(" › "), row.toolTipText)
    }
}
