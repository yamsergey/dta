package io.yamsergey.dta.sidekick.input

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.layout.LayoutInfo
import io.yamsergey.dta.sidekick.layout.compose.ToolingEnrichment
import io.yamsergey.dta.sidekick.layout.compose.ToolingTree
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 064 B2: the pressed node's path, and a pruned view mapTree reads like the whole table. */
class TapPathTest {

    /** Group objects are fresh on every read of [compositionGroups], as in the real slot table. */
    private class G(val info: String?, override val node: Any? = null, private val kids: () -> List<CompositionGroup> = { emptyList() }) : CompositionGroup {
        override val key: Any = info ?: "group"
        override val sourceInfo: String? = info
        override val data: Iterable<Any?> = emptyList()
        override val compositionGroups: Iterable<CompositionGroup> get() = kids()
        override val isEmpty: Boolean get() = kids().isEmpty()
    }

    private class Data(private val roots: () -> List<CompositionGroup>) : CompositionData {
        override val compositionGroups: Iterable<CompositionGroup> get() = roots()
        override val isEmpty: Boolean get() = roots().isEmpty()
    }

    private fun layoutNode(id: Int): LayoutInfo = Proxy.newProxyInstance(LayoutInfo::class.java.classLoader, arrayOf(LayoutInfo::class.java)) { _, m, _ ->
        when (m.name) {
            "getSemanticsId" -> id
            "hashCode" -> id
            "equals" -> false
            "toString" -> "node$id"
            else -> when (m.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        }
    } as LayoutInfo

    private val target = layoutNode(4)
    private val other = layoutNode(3)

    /**
     * Screen calls Row (line 10) and Column (line 11) at its two call sites; Column calls Text
     * (line 20) holding the pressed node. Row's subtree has its own node. Pruning that dropped Row
     * would give Column Row's call site.
     */
    private fun table() = Data {
        listOf(
            G("C(Screen)9@100L6,10@130L6,11@160L6:Screen.kt#1f2g3") {
                listOf(
                    G("C(Row)14@300L4:Screen.kt#1f2g3") { listOf(G(null, other)) },
                    G("C(Column)19@400L4:Screen.kt#1f2g3") {
                        listOf(G(null) { listOf(G("C(Text):Screen.kt#1f2g3") { listOf(G(null, target)) }) })
                    },
                )
            },
        )
    }

    private fun chain(data: CompositionData, id: Long) =
        ToolingTree.chains(listOfNotNull(ToolingEnrichment().map(data)), emptyList(), false).chains[id]

    @Test
    fun `the path is found by child index although the groups are fresh objects`() {
        val found = assertNotNull(TapPath.find(listOf(Data { emptyList() }, table()), target))
        assertEquals(1, found.table)
        assertEquals(listOf(0, 1, 0, 0, 0), found.steps)
        assertNull(TapPath.find(listOf(table()), layoutNode(99)))
    }

    @Test
    fun `the pruned view gives the same chain as the whole table`() {
        val whole = assertNotNull(chain(table(), 4))
        val found = TapPath.find(listOf(table()), target)!!
        assertEquals(whole, chain(TapPath.pruned(table(), found.steps), 4))
        assertTrue(whole.any { it.name == "Column" }, "$whole")
    }

    @Test
    fun `the pruned view leaves out the subtrees beside the path`() {
        val found = TapPath.find(listOf(table()), target)!!
        val mapped = ToolingEnrichment().map(TapPath.pruned(table(), found.steps))!!
        assertNull(ToolingTree.chains(listOf(mapped), emptyList(), false).chains[3])
    }

    @Test
    fun `one walk finds the pressed node and its layout ancestors in any table`() {
        val host = layoutNode(1)
        val hostTable = Data { listOf(G("C(Screen):Screen.kt#1f2g3") { listOf(G(null, host)) }) }
        val found = TapPath.findAll(listOf(table(), hostTable), listOf(target, layoutNode(98), host))
        assertEquals(listOf(0, 1, 0, 0, 0), found[target]!!.steps)
        assertEquals(1, found[host]!!.table)
        assertEquals(listOf(0, 0), found[host]!!.steps)
        assertNull(found[layoutNode(98)])
    }
}
