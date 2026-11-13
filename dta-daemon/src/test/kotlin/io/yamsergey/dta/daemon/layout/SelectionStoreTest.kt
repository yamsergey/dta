package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.Selections
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 055: the daemon's shared selection per app. */
class SelectionStoreTest {
    private var now = 100L
    private val store = SelectionStore { now }
    private val screen = Screen(1080, 2400, 2.6f)
    private val buy = ComposeNode(id = 160, bounds = Rect(0, 0, 10, 10), name = "Button")
    private fun capture(vararg nodes: ComposeNode) = LayoutCapture(screen, CaptureSource.SIDEKICK,
        listOf(Window("app", WindowType.APPLICATION, 0, ComposeNode(id = 1, bounds = Rect(0, 0, 100, 100), name = "Root", children = nodes.toList()))))

    @Test
    fun `one selection per app, read with freshness against the latest capture`() {
        val e = Selections.element(buy, listOf("Root", "Button"), capturedAt = 100, screen, CaptureSource.SIDEKICK)
        store.change("d", "a", "set", listOf(e), "plugin")
        assertEquals(Selections.NOT_CHECKED, store.read("d", "a").elements.single().now)
        now = 200
        store.remember("d", "a", capture(buy))
        assertEquals(Selections.ON_SCREEN, store.read("d", "a").elements.single().now)
        now = 300
        store.remember("d", "a", capture())
        assertEquals(Selections.GONE, store.read("d", "a").elements.single().now)
        assertEquals(emptyList(), store.read("d", "other").elements)
    }

    @Test
    fun `a filtered capture is not remembered`() {
        store.remember("d", "a", capture(buy).copy(filtersApplied = true))
        assertNull(store.latest("d", "a"))
    }

    @Test
    fun `the app ending clears the selection and says so`() {
        store.change("d", "a", "set", listOf(Selections.element(buy, listOf("Button"), 100, null, null)), "mcp")
        store.ended("d", "a")
        val s = store.read("d", "a")
        assertEquals(emptyList(), s.elements)
        assertEquals(Selections.ENDED, s.note)
        assertEquals(2L, s.revision)
        assertNull(store.latest("d", "a"))
    }
}
