package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Provenance
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerAssemblyTest {

    private fun sem(id: Long, text: String? = null, vararg children: Node) =
        ComposeNode(id = id, bounds = Rect(0, 0, 10, 10), text = text, children = children.toList())

    private fun rec(id: Long, parent: Long?, bounds: Rect = Rect(0, 0, 10, 10), placed: Boolean = true) =
        LayoutRecord(id, parent, bounds, placed)

    private fun label(name: String, line: Int = 1) = EnrichmentEntry(name, "Screen.kt", line, emptyList(), library = false)

    private fun Node.find(id: Long): ComposeNode? =
        if (this.id == id) this as ComposeNode else children.firstNotNullOfOrNull { it.find(id) }

    private fun Node.parentOf(id: Long): Node? =
        if (children.any { it.id == id }) this else children.firstNotNullOfOrNull { it.parentOf(id) }

    private fun Node.allIds(): List<Long> = listOfNotNull(id) + children.flatMap { it.allIds() }

    /** Semantics: root(1) > text(5). Layout: 1 > Column(2) > Row(3) > 5; 2 > Box(4). */
    private val semantic = sem(1, null, sem(5, "Hello"))
    private val records = listOf(rec(1, null), rec(2, 1), rec(3, 2), rec(4, 2), rec(5, 3))
    private val labels = mapOf(2L to label("Column", 10), 3L to label("Row", 11), 4L to label("Box", 12), 5L to label("Text", 13))

    @Test
    fun `containers are placed under their layout parent and semantic nodes move beneath them`() {
        val tree = ContainerAssembly.insertContainers(semantic, records, labels)
        assertEquals(1L, tree.parentOf(2)!!.id)
        assertEquals(2L, tree.parentOf(3)!!.id)
        assertEquals(2L, tree.parentOf(4)!!.id)
        assertEquals(3L, tree.parentOf(5)!!.id)
        val row = tree.find(3)!!
        assertEquals("Row", row.name)
        assertEquals("Screen.kt", row.sourceFile)
        assertEquals(11, row.lineNumber)
        assertEquals(Provenance.TOOLING, row.provenance)
    }

    @Test
    fun `semantic nodes keep their id, fields and semantics provenance`() {
        val tree = ContainerAssembly.insertContainers(semantic, records, labels)
        val text = tree.find(5)!!
        assertEquals("Hello", text.text)
        assertEquals(Provenance.SEMANTICS, text.provenance)
        assertEquals(Provenance.SEMANTICS, tree.find(1)!!.provenance)
    }

    @Test
    fun `a container with no semantic descendants is kept`() {
        val tree = ContainerAssembly.insertContainers(semantic, records, labels)
        assertEquals("Box", tree.find(4)!!.name)
    }

    @Test
    fun `containers carry no semantic fields`() {
        val box = ContainerAssembly.insertContainers(semantic, records, labels).find(4)!!
        assertNull(box.text)
        assertNull(box.role)
        assertNull(box.testTag)
        assertTrue(box.interactions.isEmpty())
        assertNull(box.parameters)
    }

    @Test
    fun `unplaced and zero-area containers are present but not visible`() {
        val recs = records + rec(6, 2, Rect(5, 5, 5, 5)) + rec(7, 2, placed = false)
        val tree = ContainerAssembly.insertContainers(semantic, recs, labels)
        assertEquals(false, tree.find(6)!!.visible)
        assertEquals(false, tree.find(7)!!.visible)
        assertNull(tree.find(4)!!.visible)
    }

    @Test
    fun `a wrapper with the same bounds as its child stays its own node`() {
        val recs = listOf(rec(1, null), rec(2, 1, Rect(0, 0, 5, 5)), rec(3, 2, Rect(0, 0, 5, 5)))
        val tree = ContainerAssembly.insertContainers(sem(1), recs, emptyMap())
        assertEquals(2L, tree.parentOf(3)!!.id)
    }

    @Test
    fun `subcomposition content links to its list container through the parent id`() {
        // LazyColumn container 2 hosts item text 8, which lives in another
        // composition; semantics put 8 directly under the list's semantic node 1.
        val sem = sem(1, null, sem(8, "Item 0"))
        val recs = listOf(rec(1, null), rec(2, 1), rec(8, 2))
        val tree = ContainerAssembly.insertContainers(sem, recs, mapOf(2L to label("LazyColumn")))
        assertEquals(2L, tree.parentOf(8)!!.id)
    }

    @Test
    fun `a semantic node without a layout record keeps its semantics parent`() {
        val sem = sem(1, null, sem(5, "Hello", sem(9, "fake")))
        val tree = ContainerAssembly.insertContainers(sem, records, labels)
        assertEquals(5L, tree.parentOf(9)!!.id)
    }

    @Test
    fun `a record whose parent is unknown attaches to the host root, never dropped`() {
        val tree = ContainerAssembly.insertContainers(semantic, records + rec(20, 999), labels)
        assertEquals(1L, tree.parentOf(20)!!.id)
    }

    @Test
    fun `ids are unique and every semantic id survives`() {
        val tree = ContainerAssembly.insertContainers(semantic, records, labels)
        val ids = tree.allIds()
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), ids.toSet())
    }

    @Test
    fun `siblings keep layout record order`() {
        val recs = listOf(rec(1, null), rec(3, 1), rec(2, 1), rec(4, 1))
        val tree = ContainerAssembly.insertContainers(sem(1), recs, emptyMap())
        assertEquals(listOf(3L, 2L, 4L), tree.children.map { it.id })
    }

    @Test
    fun `depth cap is reported, not silent`() {
        val chain = listOf(rec(1, null)) + (2L..60L).map { rec(it, it - 1) }
        val tree = ContainerAssembly.insertContainers(sem(1), chain, emptyMap(), maxDepth = 50)
        var n: Node = tree
        var depth = 0
        while (n.children.isNotEmpty()) { n = n.children.single(); depth++ }
        assertEquals(50, depth)
        assertTrue(n.degraded!!.any { it.reason == DegradationCodes.DEPTH_CAP_REACHED })
    }
}
