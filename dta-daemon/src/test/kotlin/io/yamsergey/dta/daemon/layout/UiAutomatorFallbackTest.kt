package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.FallbackNode
import io.yamsergey.dta.protocol.layout.Interaction
import io.yamsergey.dta.protocol.layout.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiAutomatorFallbackTest {

    private fun parse(xml: String) = UiAutomatorFallback.parseAndPrune(xml, screenWidth = 1080, screenHeight = 2400)

    private fun Node.flatten(): List<Node> = listOf(this) + children.flatMap { it.flatten() }

    @Test
    fun `parses a real recorded launcher dump into fallback nodes`() {
        val xml = javaClass.getResource("/uiautomator/launcher.xml")!!.readText()
        val root = parse(xml)
        assertTrue(root.flatten().all { it is FallbackNode })
        assertTrue(root.flatten().isNotEmpty())
    }

    @Test
    fun `bounds are parsed from the android bracket format`() {
        val xml = wrap(
            """<node class="android.widget.TextView" text="Hello" bounds="[10,20][110,80]" clickable="false" />""",
        )
        val node = parse(xml).flatten().first { it.text == "Hello" }
        assertEquals(10, node.bounds.left)
        assertEquals(20, node.bounds.top)
        assertEquals(110, node.bounds.right)
        assertEquals(80, node.bounds.bottom)
    }

    @Test
    fun `zero-area and system-bar nodes are dropped`() {
        val xml = wrap(
            """<node class="android.view.View" text="Keep" bounds="[0,0][100,100]" />""" +
                """<node class="android.view.View" text="ZeroArea" bounds="[5,5][5,5]" />""" +
                """<node class="android.view.View" resource-id="android:id/statusBarBackground" bounds="[0,0][1080,60]" />""",
        )
        val texts = parse(xml).flatten().mapNotNull { it.text }
        assertTrue("Keep" in texts)
        assertTrue("ZeroArea" !in texts, "zero-area dropped")
        assertTrue(parse(xml).flatten().none { (it as FallbackNode).resourceId?.contains("statusBar") == true })
    }

    @Test
    fun `uninteresting single-child chain collapses`() {
        // Wrapper with no text/id/interaction and one child → child promoted.
        val xml = wrap(
            """<node class="android.widget.FrameLayout" bounds="[0,0][100,100]">""" +
                """<node class="android.widget.TextView" text="Label" bounds="[0,0][100,50]" /></node>""",
        )
        val root = parse(xml)
        // The frame wrapper should not survive as its own labeled node around one child.
        val labels = root.flatten().mapNotNull { it.text }
        assertTrue("Label" in labels)
        assertTrue(root.flatten().count { it.text == null && it.children.size == 1 } == 0)
    }

    @Test
    fun `clickable unlabeled parent fuses its single labeled leaf`() {
        val xml = wrap(
            """<node class="android.widget.LinearLayout" clickable="true" bounds="[0,0][200,80]">""" +
                """<node class="android.widget.TextView" text="Submit" clickable="false" bounds="[10,10][190,70]" /></node>""",
        )
        val fused = parse(xml).flatten().first { it.interactions.contains(Interaction.CLICKABLE) }
        assertEquals("Submit", fused.text, "label fused up onto the clickable parent")
        assertTrue(fused.children.isEmpty(), "labeled leaf consumed by fusion")
    }

    @Test
    fun `interaction and state flags map onto fallback nodes`() {
        val xml = wrap(
            """<node class="android.widget.CheckBox" text="Agree" bounds="[0,0][100,40]" """ +
                """clickable="true" checkable="true" checked="true" focusable="true" scrollable="false" />""",
        )
        val node = parse(xml).flatten().first { it.text == "Agree" } as FallbackNode
        assertTrue(Interaction.CLICKABLE in node.interactions)
        assertTrue(Interaction.FOCUSABLE in node.interactions)
        assertEquals(true, node.checked)
    }

    private fun wrap(inner: String) =
        """<?xml version='1.0'?><hierarchy rotation="0"><node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">$inner</node></hierarchy>"""
}
