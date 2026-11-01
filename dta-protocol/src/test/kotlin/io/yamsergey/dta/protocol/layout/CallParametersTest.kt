package io.yamsergey.dta.protocol.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 018 (T008): a call's parameters once per capture; defaults counted below composition. */
class CallParametersTest {
    private val r = Rect(0, 0, 10, 10)
    private val passed = Parameter("title", "<lambda>", "lambda")
    private val modifier = Parameter("modifier", "<TestTagElement>", "TestTagElement")
    private val defaulted = Parameter("colors", "<TopAppBarColors>", "TopAppBarColors", default = true)
    private val params = listOf(passed, modifier, defaulted)

    private fun c(id: Long, call: Int?, vararg kids: Node, name: String = "CenterAlignedTopAppBar", p: List<Parameter>? = params) =
        ComposeNode(id = id, bounds = r, name = name, sourceFile = "TopAppBar.kt", lineNumber = 52, parameters = p, callKey = call, children = kids.toList())

    private fun flat(n: Node): List<ComposeNode> = listOfNotNull(n as? ComposeNode) + n.children.flatMap(::flat)

    @Test
    fun `one call over three nodes - parameters on the first, the others point to it`() {
        val tree = c(54, 1, c(55, 1, c(59, 1)))
        val (a, b, d) = flat(CallParameters.finish(tree, keepDefaults = false))
        assertEquals(listOf(passed, modifier), a.parameters)
        assertEquals(1, a.defaultParameters)
        assertNull(a.sameCallAs)
        assertEquals(54, b.sameCallAs)
        assertNull(b.parameters)
        assertEquals(54, d.sameCallAs)
        assertEquals("CenterAlignedTopAppBar", d.name, "the inner nodes still name the call")
    }

    @Test
    fun `two calls of the same composable keep their own`() {
        val tree = c(1, null, c(2, 7, name = "IconButton"), c(3, 8, name = "IconButton"), p = null)
        val nodes = flat(CallParameters.finish(tree, keepDefaults = false))
        assertEquals(listOf(passed, modifier), nodes[1].parameters)
        assertEquals(listOf(passed, modifier), nodes[2].parameters)
        assertNull(nodes[2].sameCallAs)
    }

    @Test
    fun `a container first in tree order carries them, even when it had none`() {
        val tree = c(10, 1, c(11, 1), p = null)
        val (container, inner) = flat(CallParameters.finish(tree, keepDefaults = false))
        assertEquals(listOf(passed, modifier), container.parameters)
        assertEquals(10, inner.sameCallAs)
    }

    @Test
    fun `at composition the defaults stay, marked`() {
        val root = flat(CallParameters.finish(c(54, 1, c(55, 1)), keepDefaults = true)).first()
        assertEquals(params, root.parameters)
        assertNull(root.defaultParameters)
    }

    @Test
    fun `only defaults - no list, a count`() {
        val root = flat(CallParameters.finish(c(1, 3, p = listOf(defaulted)), keepDefaults = false)).single()
        assertNull(root.parameters)
        assertEquals(1, root.defaultParameters)
    }

    @Test
    fun `nodes without a call are untouched, defaults still dropped below composition`() {
        val tree = ViewNode(id = 1, bounds = r, className = "android.widget.FrameLayout", children = listOf(c(2, null)))
        val node = flat(CallParameters.finish(tree, keepDefaults = false)).single()
        assertEquals(listOf(passed, modifier), node.parameters)
        assertNull(node.sameCallAs)
    }
}
