package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.Parameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parity for feature-002 labeling, now as a pure function over the mapTree
 * intermediate (feature 003 T013). */
class ToolingTreeTest {

    private fun call(
        name: String?,
        file: String? = null,
        line: Int? = null,
        params: List<Parameter> = emptyList(),
        vararg children: ToolingGroup,
    ) = ToolingGroup(name, file, line, params, layoutId = null, children = children.toList())

    private fun node(layoutId: Long, vararg children: ToolingGroup) =
        ToolingGroup(null, null, null, emptyList(), layoutId = layoutId, children = children.toList())

    /** ComposeScreen shape: ComposeContent > Column > Text, SubmitButton >
     * Button > Surface > ... > node(7). Locations are call sites. */
    private val composeScreen = listOf(
        call(
            "ComposeContent", "ComposeScreens.kt", 22, emptyList(),
            call(
                "Column", "ComposeScreens.kt", 28, emptyList(),
                node(
                    3,
                    call(
                        "Text", "ComposeScreens.kt", 29, emptyList(),
                        call("BasicText", "Text.kt", 150, emptyList(), call("Layout", "BasicText.kt", 80, emptyList(), node(5))),
                    ),
                    call(
                        "SubmitButton", "ComposeScreens.kt", 30, listOf(Parameter("enabled", "true", "Boolean")),
                        call(
                            "Button", "ComposeScreens.kt", 37, emptyList(),
                            call("Surface", "Button.kt", 120, emptyList(), call("Layout", "Surface.kt", 300, emptyList(), node(7))),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `nearest user composable labels the node`() {
        val labels = ToolingTree.label(composeScreen)
        assertEquals("Button", labels[7]!!.name)
        assertEquals("ComposeScreens.kt", labels[7]!!.sourceFile)
        assertEquals(37, labels[7]!!.lineNumber)
        assertEquals(false, labels[7]!!.library)
        // BasicText is normalized, but Text (user call site) is nearer-user.
        assertEquals("Text", labels[5]!!.name)
        assertEquals(29, labels[5]!!.lineNumber)
    }

    @Test
    fun `parameters come from the chosen frame`() {
        val tree = listOf(
            call(
                "SubmitButton", "ComposeScreens.kt", 30, listOf(Parameter("enabled", "true", "Boolean")),
                call("Surface", "Button.kt", 120, listOf(Parameter("shape", "x", "Shape")), node(7)),
            ),
        )
        val entry = ToolingTree.label(tree)[7]!!
        assertEquals("SubmitButton", entry.name)
        assertEquals(listOf(Parameter("enabled", "true", "Boolean")), entry.parameters)
    }

    @Test
    fun `library composable is the fallback when no user composable encloses the node`() {
        val tree = listOf(call("Surface", "Surface.kt", 10, emptyList(), call("Layout", "Layout.kt", 5, emptyList(), node(4))))
        val entry = ToolingTree.label(tree)[4]!!
        assertEquals("Surface", entry.name)
        assertEquals(true, entry.library)
    }

    @Test
    fun `skip names never label and unnamed context yields no entry`() {
        val tree = listOf(call("Layout", "Layout.kt", 1, emptyList(), node(2)), node(3))
        val labels = ToolingTree.label(tree)
        assertNull(labels[2])
        assertNull(labels[3])
    }

    @Test
    fun `first id seen wins`() {
        val tree = listOf(
            call("First", "A.kt", 1, emptyList(), node(9)),
            call("Second", "B.kt", 2, emptyList(), node(9)),
        )
        assertEquals("First", ToolingTree.label(tree)[9]!!.name)
    }

    @Test
    fun `ids inside a nested node group are labeled by the enclosing composable`() {
        val labels = ToolingTree.label(composeScreen)
        // node 3 (the Column's layout node) is labeled by Column, a user call site.
        assertEquals("Column", labels[3]!!.name)
        assertTrue(labels.keys == setOf(3L, 5L, 7L), labels.keys.toString())
    }
}
