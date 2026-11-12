package io.yamsergey.dta.protocol.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Feature 055: the shared selection's rules. */
class SelectionsTest {
    private val buy = ComposeNode(id = 160, bounds = Rect(40, 1000, 500, 1100), name = "Button", text = "Buy 1 ACME")
    private val price = ComposeNode(id = 150, bounds = Rect(40, 300, 500, 380), name = "Text", text = "19.27")
    private val root = ViewNode(id = 1, bounds = Rect(0, 0, 1080, 2400), className = "com.android.internal.policy.DecorView",
        children = listOf(ComposeNode(id = 100, bounds = Rect(0, 0, 1080, 2400), name = "Scaffold", children = listOf(price, buy))))
    private val sheet = ComposeNode(id = 900, bounds = Rect(0, 1800, 1080, 2400), name = "ModalBottomSheet")
    private val screen = Screen(1080, 2400, 2.625f, "io.x.MainActivity")
    private fun capture(vararg w: Window) = LayoutCapture(screen, CaptureSource.SIDEKICK, w.toList())
    private val main = capture(Window("app", WindowType.APPLICATION, 0, root))

    @Test
    fun `an element is the node without children, its path and capture`() {
        val e = Selections.element(root.children[0], listOf("DecorView", "Scaffold"), 10, screen, CaptureSource.SIDEKICK)
        assertNull(e.node["children"])
        assertEquals("1080×2400 · MainActivity", e.screen)
        assertEquals("sidekick", e.source)
    }

    @Test
    fun `ids are found with their paths, missing ids are named`() {
        val (found, missing) = Selections.byIds(main, listOf(160, 42))
        assertEquals(listOf(listOf("DecorView", "Scaffold", "Button")), found.map { it.second })
        assertEquals(listOf(42L), missing)
    }

    @Test
    fun `a point picks the innermost node of the topmost window holding it`() {
        assertEquals(160L, Selections.at(main, 100, 1050)!!.first.id)
        assertEquals(100L, Selections.at(main, 900, 50)!!.first.id)
        val withSheet = capture(Window("app", WindowType.APPLICATION, 0, root), Window("sheet", WindowType.APPLICATION, 1, sheet))
        assertEquals(900L, Selections.at(withSheet, 100, 2000)!!.first.id)
        assertNull(Selections.at(main, 2000, 50))
        assertEquals(300 to 1200, Selections.point("300, 1200"))
        assertNull(Selections.point("300"))
    }

    @Test
    fun `fresh when a newer capture has the same node, gone when it lacks it, not checked otherwise`() {
        val e = Selections.element(buy, listOf("Button"), capturedAt = 10, screen, CaptureSource.SIDEKICK)
        assertEquals(Selections.NOT_CHECKED, Selections.freshness(e, main, 10))
        assertEquals(Selections.NOT_CHECKED, Selections.freshness(e, null, null))
        assertEquals(Selections.ON_SCREEN, Selections.freshness(e, main, 20))
        val elsewhere = capture(Window("app", WindowType.APPLICATION, 0, ViewNode(id = 1, bounds = Rect(0, 0, 1, 1), className = "DecorView")))
        assertEquals(Selections.GONE, Selections.freshness(e, elsewhere, 20))
        // Same id, another composable: not the same element.
        val other = capture(Window("app", WindowType.APPLICATION, 0, ComposeNode(id = 160, bounds = Rect(0, 0, 1, 1), name = "Text")))
        assertEquals(Selections.GONE, Selections.freshness(e, other, 20))
    }

    @Test
    fun `set, add, remove and clear keep order, drop doubles and count changes`() {
        val a = Selections.element(buy, listOf("Button"), 10, screen, null)
        val b = Selections.element(price, listOf("Text"), 10, screen, null)
        var s = LayoutSelection("emulator-5554", "io.x")
        s = Selections.apply(s, "set", listOf(a), "plugin", 1)
        s = Selections.apply(s, "add", listOf(b, a), "mcp", 2)
        assertEquals(listOf(160L, 150L), s.elements.map { it.node["id"].toString().toLong() })
        assertEquals("mcp" to 2L, s.changedBy to s.revision)
        s = Selections.apply(s, "remove", listOf(a), "cli", 3)
        assertEquals(listOf(150L), s.elements.map { it.node["id"].toString().toLong() })
        s = Selections.apply(s, "clear", emptyList(), "plugin", 4)
        assertEquals(emptyList(), s.elements)
        assertFailsWith<IllegalArgumentException> { Selections.apply(s, "toggle", emptyList(), "x", 5) }
    }

    @Test
    fun `beyond the bound the rest is counted, not kept`() {
        val many = (1..60).map { Selections.element(ComposeNode(id = it.toLong(), bounds = Rect(0, 0, 1, 1), name = "Text"), listOf("Text"), 1, null, null) }
        val s = Selections.apply(LayoutSelection("d", "a"), "set", many, "mcp", 1)
        assertEquals(50, s.elements.size)
        assertEquals(10, s.omitted)
    }
}
