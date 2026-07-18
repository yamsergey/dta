package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM merge logic (constitution IV): operates on capture intermediates,
 * no Android. Proves the two hard cases — compose-under-host and
 * embedded-view-by-identity — plus degradation pass-through.
 */
class TreeMergerTest {

    private fun view(id: Long, cls: String = "V", children: List<Node> = emptyList()) =
        ViewNode(id = id, bounds = Rect(0, 0, 10, 10), className = cls, children = children)

    private fun compose(id: Long, name: String, children: List<Node> = emptyList()) =
        ComposeNode(id = id, bounds = Rect(0, 0, 10, 10), name = name, children = children)

    @Test
    fun `compose tree is attached under its host view node`() {
        val viewWalk = ViewWalkResult(
            root = view(1, "FrameLayout", listOf(view(99, "ComposeView"))),
            composeHostIds = setOf(99),
        )
        val composeWalk = ComposeWalkResult(
            hostDrawingId = 99,
            tree = compose(100, "MyScreen", listOf(compose(101, "Text"))),
        )

        val merged = TreeMerger.merge(viewWalk, listOf(composeWalk))

        val host = merged.children.single()
        assertIs<ViewNode>(host)
        assertEquals("ComposeView", host.className)
        val composeRoot = host.children.single() as ComposeNode
        assertEquals("MyScreen", composeRoot.name)
        assertEquals("Text", (composeRoot.children.single() as ComposeNode).name)
    }

    @Test
    fun `embedded view is reparented under its compose parent BY ID, not by order`() {
        val embeddedView = view(7, "WebView")
        val viewWalk = ViewWalkResult(
            root = view(1, "Root", listOf(view(50, "ComposeView"))),
            embeddedViewRoots = mapOf(7L to embeddedView),
            composeHostIds = setOf(50),
        )
        // Decoy first, true parent second: ordinal matching would misplace it.
        val composeParent = compose(200, "AndroidViewHost")
        val composeWalk = ComposeWalkResult(
            hostDrawingId = 50,
            tree = compose(199, "Screen", listOf(compose(198, "Decoy"), composeParent)),
            embeddedHostDrawingIds = listOf(EmbeddedHost(composeNodeId = 200, viewDrawingId = 7)),
        )

        val merged = TreeMerger.merge(viewWalk, listOf(composeWalk))

        val screen = (merged.children.single() as ViewNode).children.single() as ComposeNode
        val decoy = screen.children[0] as ComposeNode
        val host = screen.children[1] as ComposeNode
        assertEquals("Decoy", decoy.name)
        assertTrue(decoy.children.isEmpty(), "embedded view must NOT land on the decoy")
        assertEquals("AndroidViewHost", host.name)
        assertEquals(7L, host.children.single().id, "embedded view reparented by id")
    }

    @Test
    fun `multiple compose hosts in one window each get their own subtree`() {
        val viewWalk = ViewWalkResult(
            root = view(1, "Root", listOf(view(10, "ComposeView"), view(20, "ComposeView"))),
            composeHostIds = setOf(10, 20),
        )
        val walks = listOf(
            ComposeWalkResult(10, compose(11, "First")),
            ComposeWalkResult(20, compose(21, "Second")),
        )

        val merged = TreeMerger.merge(viewWalk, walks)

        assertEquals(
            listOf("First", "Second"),
            merged.children.map { (it as ViewNode).children.single() as ComposeNode }.map { it.name },
        )
    }

    @Test
    fun `host with no matching compose walk gets a first-frame-pending marker`() {
        val viewWalk = ViewWalkResult(
            root = view(1, "Root", listOf(view(77, "ComposeView"))),
            composeHostIds = setOf(77),
        )

        val merged = TreeMerger.merge(viewWalk, emptyList())

        val composeChild = (merged.children.single() as ViewNode).children.single()
        assertTrue(
            composeChild.degraded!!.any { it.reason == DegradationCodes.COMPOSE_FIRST_FRAME_PENDING },
            "unmatched host must be visibly degraded, not silently dropped",
        )
    }

    @Test
    fun `depth-cap degradation on an input node is preserved through merge`() {
        val capped = view(1, "Deep").copy(
            degraded = listOf(DegradationMarker("layout", DegradationCodes.DEPTH_CAP_REACHED)),
        )
        val merged = TreeMerger.merge(ViewWalkResult(capped), emptyList())
        assertEquals(DegradationCodes.DEPTH_CAP_REACHED, merged.degraded!!.single().reason)
    }

    @Test
    fun `a pure view tree passes through unchanged`() {
        val tree = view(1, "A", listOf(view(2, "B")))
        val merged = TreeMerger.merge(ViewWalkResult(tree), emptyList())
        assertEquals(tree, merged)
        assertNull(merged.degraded)
    }
}
