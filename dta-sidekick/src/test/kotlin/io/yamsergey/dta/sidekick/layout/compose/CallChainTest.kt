package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.Frame
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Root → node call chains over the mapTree intermediate (feature 003 US3). */
class CallChainTest {

    private val app = ComposableClassifier.packageHash("com.example.app")
    private val foundation = ComposableClassifier.packageHash("androidx.compose.foundation.layout")
    private val lazy = ComposableClassifier.packageHash("androidx.compose.foundation.lazy.layout")

    private fun user(name: String, line: Int, vararg children: ToolingGroup) =
        ToolingGroup(name, "Screen.kt", line, emptyList(), null, children.toList(), packageHash = app)

    private fun lib(name: String, pkg: Int = foundation, vararg children: ToolingGroup) =
        ToolingGroup(name, "Lib.kt", 7, emptyList(), null, children.toList(), packageHash = pkg)

    private fun node(id: Long, vararg children: ToolingGroup) =
        ToolingGroup(null, null, null, emptyList(), id, children.toList())

    private fun rec(id: Long, parent: Long?) = LayoutRecord(id, parent, Rect(0, 0, 1, 1), placed = true)

    private fun names(chain: List<Frame>?) = chain!!.map { it.name }

    /** Main composition: Screen > Column(lib internals) > node 2 > Card > Row > node 3 > Text > node 4. */
    private val main = user(
        "Screen", 10,
        user(
            "Column", 11,
            lib(
                "Layout", foundation,
                node(
                    2,
                    user("Card", 12, user("Row", 20, node(3, user("Text", 21, lib("BasicText", foundation, node(4)))))),
                ),
            ),
        ),
    )
    private val records = listOf(rec(1, null), rec(2, 1), rec(3, 2), rec(4, 3))

    @Test
    fun `chains thread top-down through user frames, root first`() {
        val result = ToolingTree.chains(listOf(main), records, frameworkFrames = false)
        assertEquals(listOf("Screen", "Column"), names(result.chains[2]))
        assertEquals(listOf("Screen", "Column", "Card", "Row"), names(result.chains[3]))
        assertEquals(listOf("Screen", "Column", "Card", "Row", "Text"), names(result.chains[4]))
        val text = result.chains[4]!!.last()
        assertEquals(Frame("Text", "Screen.kt", 21), text)
        assertTrue(!result.unresolvedPrefix)
    }

    @Test
    fun `skip names never appear and framework frames are excluded by default`() {
        val result = ToolingTree.chains(listOf(main), records, frameworkFrames = false)
        assertTrue(result.chains.values.flatten().none { it.name == "Layout" || it.name == "BasicText" || it.name == "Text" && it.library == true })
        assertTrue(result.chains.values.flatten().none { it.library == true })
    }

    @Test
    fun `framework frames appear marked when requested, skip names still excluded`() {
        val result = ToolingTree.chains(listOf(main), records, frameworkFrames = true)
        val chain = result.chains[4]!!
        assertEquals(listOf("Screen", "Column", "Card", "Row", "Text", "Text"), names(chain))
        assertEquals(true, chain.last().library)
        assertTrue(chain.none { it.name == "Layout" })
    }

    @Test
    fun `a subcomposition chain is prefixed by its host layout node's chain`() {
        // LazyColumn's node 5 lives in main; item content is another
        // composition whose top layout node 6 has layout parent 5.
        val withList = user("Screen", 10, user("LazyColumn", 30, lib("LazyLayout", lazy, node(5))))
        val item = lib("SubcomposeItem", lazy, user("Text", 31, node(6)))
        val recs = listOf(rec(1, null), rec(5, 1), rec(6, 5))
        val result = ToolingTree.chains(listOf(withList, item), recs, frameworkFrames = false)
        assertEquals(listOf("Screen", "LazyColumn"), names(result.chains[5]))
        assertEquals(listOf("Screen", "LazyColumn", "Text"), names(result.chains[6]))
        assertTrue(!result.unresolvedPrefix)
    }

    @Test
    fun `nested subcompositions resolve transitively regardless of composition order`() {
        val outer = user("Screen", 1, node(5))
        val middle = user("Pager", 2, node(6))
        val inner = user("Page", 3, node(7))
        val recs = listOf(rec(1, null), rec(5, 1), rec(6, 5), rec(7, 6))
        val result = ToolingTree.chains(listOf(inner, middle, outer), recs, frameworkFrames = false)
        assertEquals(listOf("Screen", "Pager", "Page"), names(result.chains[7]))
    }

    @Test
    fun `a reused composable gets a distinct chain per call path`() {
        val tree = user(
            "Screen", 1,
            user("Header", 2, user("Badge", 40, node(8))),
            user("Footer", 3, user("Badge", 40, node(9))),
        )
        val result = ToolingTree.chains(listOf(tree), listOf(rec(1, null), rec(8, 1), rec(9, 1)), frameworkFrames = false)
        assertEquals(listOf("Screen", "Header", "Badge"), names(result.chains[8]))
        assertEquals(listOf("Screen", "Footer", "Badge"), names(result.chains[9]))
    }

    @Test
    fun `an unresolvable subcomposition parent yields the local chain and is flagged`() {
        val item = user("Item", 5, node(6))
        val result = ToolingTree.chains(listOf(main, item), records + rec(6, 999), frameworkFrames = false)
        assertEquals(listOf("Item"), names(result.chains[6]))
        assertTrue(result.unresolvedPrefix)
    }
}
