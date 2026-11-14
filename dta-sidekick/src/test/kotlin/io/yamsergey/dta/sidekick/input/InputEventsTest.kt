package io.yamsergey.dta.sidekick.input

import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 016 (T019): clicks and back as timeline events. */
class InputEventsTest {
    private val button = InputEvents.Target(name = "delete_button", className = "MaterialButton", text = "Delete", role = "button", secure = false, windowId = "w1")

    @Test
    fun `a click names the element, its text and window`() {
        val e = InputEvents.click("7:1", 1, 100, 1_100, button, via = "view")
        assertEquals(Sources.INPUT, e.source)
        assertEquals(Kinds.CLICK, e.kind)
        assertEquals(InputAction.CLICK, e.input!!.action)
        assertEquals("delete_button|MaterialButton", e.input!!.element!!.key)
        assertEquals("button", e.input!!.element!!.role)
        assertEquals("Delete", e.input!!.text)
        assertEquals("w1", e.input!!.windowId)
        assertEquals("click delete_button \"Delete\"", e.title)
    }

    @Test
    fun `in a secure window the text is withheld and that is said`() {
        val e = InputEvents.click("7:1", 1, 100, 1_100, button.copy(secure = true), via = "view")
        assertNull(e.input!!.text)
        assertTrue(e.completeness.single().reason.startsWith("ui-secure-window"))
    }

    @Test
    fun `an element without a name is known by its class`() {
        val e = InputEvents.click("7:1", 1, 100, 1_100, button.copy(name = null, text = null), via = "view")
        assertEquals("MaterialButton", e.input!!.element!!.key)
        assertEquals("click MaterialButton", e.title)
    }

    @Test
    fun `back is recorded once even when the activity hands it to the dispatcher`() {
        assertTrue(InputEvents.recordBack(insideKind = null))
        assertFalse(InputEvents.recordBack(insideKind = Kinds.BACK))
        assertTrue(InputEvents.recordBack(insideKind = Kinds.CLICK), "a back pressed from a click handler is its own back")
        assertEquals("back", InputEvents.back("7:2", 2, 1, 1, via = "dispatcher").title)
    }

    @Test
    fun `long texts are cut`() {
        val e = InputEvents.click("7:1", 1, 100, 1_100, button.copy(text = "x".repeat(300)), via = "view")
        assertEquals(120, e.input!!.text!!.length)
    }

    private val frames = listOf(io.yamsergey.dta.protocol.layout.Frame("ListItem", "BrokerNav.kt", 103, library = false))

    @Test
    fun `a compose click carries the chain read at the tap and its time (064)`() {
        val e = InputEvents.click("7:1", 1, 100, 1_100, button, via = "compose", chain = InputEvents.TapChain(frames, partial = false, cut = null, mainThreadMs = 6))
        assertEquals(frames, e.input!!.chain)
        assertEquals(6, e.input!!.chainMainThreadMs)
        assertTrue(e.completeness.isEmpty())
    }

    @Test
    fun `a chain that could not be read says why, and a partial one says so (064)`() {
        val cut = InputEvents.click("7:1", 1, 100, 1_100, button, via = "compose", chain = InputEvents.TapChain(null, partial = false, cut = "node-not-found", mainThreadMs = 1))
        assertNull(cut.input!!.chain)
        assertEquals(1, cut.input!!.chainMainThreadMs)
        assertEquals("tap-chain-unavailable: node-not-found", cut.completeness.single().reason)
        val partial = InputEvents.click("7:1", 1, 100, 1_100, button, via = "compose", chain = InputEvents.TapChain(frames, partial = true, cut = null, mainThreadMs = 4))
        assertEquals(frames, partial.input!!.chain)
        assertTrue(partial.completeness.single().reason.startsWith("tap-chain-partial"))
    }

    @Test
    fun `a scroll names its container, where it went and its chain (064 C3)`() {
        val g = ScrollTracker.Gesture(Any(), 100, 750, io.yamsergey.dta.protocol.timeline.ScrollPosition(0, 361), io.yamsergey.dta.protocol.timeline.ScrollPosition(8, 393),
            io.yamsergey.dta.protocol.timeline.ScrollDirection.DOWN, fling = true, stoppedByTouch = false)
        val list = InputEvents.Target("markets", "LazyColumn", null, null, secure = false, windowId = "w1")
        val e = InputEvents.scroll("7:3", 3, wallStart = 5_000, g, list, InputEvents.TapChain(frames, false, null, 7))
        assertEquals(Kinds.SCROLL, e.kind)
        assertEquals(100L, e.monoMs, "the drag's start")
        assertEquals(InputAction.SCROLL, e.input!!.action)
        assertEquals("markets|LazyColumn", e.input!!.element!!.key)
        assertEquals(750L, e.input!!.scroll!!.endMonoMs)
        assertEquals(true, e.input!!.scroll!!.fling)
        assertEquals(frames, e.input!!.chain)
        assertEquals("scroll down markets", e.title)
    }
}
