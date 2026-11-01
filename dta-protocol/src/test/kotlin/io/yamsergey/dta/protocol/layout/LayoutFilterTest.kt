package io.yamsergey.dta.protocol.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutFilterTest {

    private fun view(id: Long, cls: String, text: String? = null, resId: String? = null, children: List<Node> = emptyList()) =
        ViewNode(id = id, bounds = Rect(0, 0, 10, 10), className = cls, text = text, resourceId = resId, children = children)

    private val tree = view(
        1, "android.widget.FrameLayout", resId = "com.x:id/root",
        children = listOf(
            view(2, "android.widget.LinearLayout", children = listOf(
                view(3, "android.widget.Button", text = "Submit form", resId = "com.x:id/submit"),
                view(4, "android.widget.TextView", text = "Cancel"),
            )),
            ComposeNode(id = 5, bounds = Rect(0, 0, 10, 10), name = "Button", text = "Compose Submit"),
        ),
    )

    @Test
    fun `a matched node of a call takes the call's parameters when the node carrying them is not matched`() {
        val p = listOf(Parameter("title", "<lambda>", "lambda"))
        val root = ComposeNode(
            id = 54, bounds = Rect(0, 0, 10, 10), name = "CenterAlignedTopAppBar", parameters = p, defaultParameters = 3,
            children = listOf(ComposeNode(id = 59, bounds = Rect(0, 0, 10, 10), name = "CenterAlignedTopAppBar", text = "Now in Android", sameCallAs = 54)),
        )
        val match = LayoutFilter(text = "now in").apply(root, 0).single().node as ComposeNode
        assertEquals(p, match.parameters)
        assertEquals(3, match.defaultParameters)
        assertEquals(null, match.sameCallAs)
        val both = LayoutFilter(type = "CenterAlignedTopAppBar").apply(root, 0).map { it.node as ComposeNode }
        assertEquals(54, both[1].sameCallAs, "when its owner is matched too, the reference stays")
    }

    @Test
    fun `text filter matches over text case-insensitively with ancestor chain`() {
        val matches = LayoutFilter(text = "submit").apply(tree, windowIndex = 0)
        assertEquals(setOf(3L, 5L), matches.map { it.node.id }.toSet())
        val buttonMatch = matches.first { it.node.id == 3L }
        assertEquals(listOf(1L, 2L), buttonMatch.ancestors.map { it.id })
    }

    @Test
    fun `type filter matches view class suffix and composable name exactly`() {
        val byView = LayoutFilter(type = "Button").apply(tree, 0)
        // "Button" suffix-matches android.widget.Button AND exact-matches composable "Button"
        assertEquals(setOf(3L, 5L), byView.map { it.node.id }.toSet())
    }

    @Test
    fun `resource id filter is exact`() {
        val matches = LayoutFilter(resourceId = "com.x:id/submit").apply(tree, 0)
        assertEquals(listOf(3L), matches.map { it.node.id })
    }

    @Test
    fun `multiple filters AND-combine - decoy matching only one is excluded`() {
        // text "submit" matches nodes 3 and 5; type "TextView" matches only node 4.
        // AND of (text=submit, type=Button) → only node 3 (view Button) and 5 (compose Button).
        val matches = LayoutFilter(text = "submit", type = "Button").apply(tree, 0)
        assertEquals(setOf(3L, 5L), matches.map { it.node.id }.toSet())

        // text "cancel" AND type "Button" → nothing (Cancel is a TextView).
        assertTrue(LayoutFilter(text = "cancel", type = "Button").apply(tree, 0).isEmpty())
    }

    @Test
    fun `rootId restricts matching to a subtree`() {
        // Only the LinearLayout subtree (id 2); compose Submit (id 5) is outside it.
        val matches = LayoutFilter(text = "submit", rootId = 2).apply(tree, 0)
        assertEquals(listOf(3L), matches.map { it.node.id })
    }

    @Test
    fun `zero matches returns empty list`() {
        assertTrue(LayoutFilter(text = "nonexistent").apply(tree, 0).isEmpty())
    }

    @Test
    fun `ancestor refs are light with no children`() {
        val match = LayoutFilter(resourceId = "com.x:id/submit").apply(tree, 0).single()
        assertTrue(match.ancestors.all { it.label.isNotEmpty() })
        assertEquals(0, match.window)
    }
}
