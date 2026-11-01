package io.yamsergey.dta.sidekick.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 016 (T050): the composable a click landed on, from the merged semantics tree. */
class ComposeClicksTest {
    private val tree = ComposeClicks.Node(
        id = 1, children = listOf(
            ComposeClicks.Node(id = 5, testTag = "page_title", text = "Catalog"),
            ComposeClicks.Node(id = 7, testTag = "open_product", text = "Shoes", role = "Button"),
        ),
    )

    @Test
    fun `the clicked node is found by its semantics id, with its tag, merged text and role`() {
        val t = ComposeClicks.target(tree, id = 7, secure = false, windowId = "w1")!!
        assertEquals("open_product", t.name)
        assertEquals("Button", t.className)
        assertEquals("Shoes", t.text)
        assertEquals("button", t.role)
        assertEquals("w1", t.windowId)
    }

    @Test
    fun `a node without a tag is known by its role, or as a composable`() {
        val t = ComposeClicks.target(ComposeClicks.Node(id = 3, text = "Hat"), id = 3, secure = false, windowId = null)!!
        assertNull(t.name)
        assertEquals("Composable", t.className)
    }

    @Test
    fun `an id that is not in the tree gives no target`() {
        assertNull(ComposeClicks.target(tree, id = 99, secure = false, windowId = null))
    }
}
