package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Kinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 064 (US4, T034): a coroutine launched during a click carries it to what it and its children do. */
class LineageTagsTest {
    /** A coroutine (its root) and a suspend-function frame whose completion leads to it. */
    private class Root(var done: Boolean = false)
    private class Frame(val completion: Any)

    private var now = 0L
    private val liveChanges = ArrayList<Boolean>()
    private val tags = LineageTags(
        clock = { now },
        rootOf = { f -> var x = f; while (x is Frame) x = x.completion; x },
        isDone = { (it as? Root)?.done == true },
        onLive = { liveChanges += it },
    )
    private val click = CauseLink("7:1", Basis.EXACT, CauseRules.issuedDuring(Kinds.CLICK))

    @Test
    fun `a coroutine created inside a click scope gets that click, under its own rule`() {
        val root = Root()
        tags.created(root, click, Kinds.CLICK)
        tags.resumed(Frame(Frame(root)))
        val cause = tags.current()!!
        assertEquals("7:1", cause.from)
        assertEquals(Basis.EXACT, cause.basis)
        assertEquals("launched-during:click", cause.rule)
        assertEquals(listOf(true), liveChanges, "the first live tag asks for the resume hooks")
    }

    @Test
    fun `outside any scope and any tagged coroutine nothing is tagged`() {
        tags.created(Root(), null, null)
        assertEquals(0, tags.live())
        assertTrue(liveChanges.isEmpty())
    }

    @Test
    fun `a child created while a tagged coroutine runs on this thread inherits its tag`() {
        val parent = Root()
        tags.created(parent, click, Kinds.CLICK)
        tags.resumed(Frame(parent))
        val child = Root()
        tags.created(child, null, null)
        tags.suspended(Frame(parent))
        assertNull(tags.current())
        tags.resumed(Frame(child))
        assertEquals("7:1", tags.current()?.from)
        assertEquals("launched-during:click", tags.current()?.rule)
        assertEquals(2, tags.live())
    }

    @Test
    fun `resume sets the thread's cause, suspend clears it, an untagged resume clears a stale one`() {
        val root = Root()
        tags.created(root, click, Kinds.CLICK)
        tags.resumed(Frame(root))
        assertEquals("7:1", tags.current()?.from)
        tags.suspended(Frame(root))
        assertNull(tags.current())
        tags.resumed(Frame(root))
        tags.resumed(Frame(Root()))
        assertNull(tags.current(), "another coroutine resumed on this thread: not the click's")
    }

    @Test
    fun `a completed root expires its tag and the hooks are no longer wanted`() {
        val root = Root()
        tags.created(root, click, Kinds.CLICK)
        tags.resumed(Frame(root))
        root.done = true
        tags.sweep()
        assertEquals(0, tags.live())
        assertNull(tags.current(), "an expired tag never links, even if this thread still holds it")
        assertEquals(listOf(true, false), liveChanges)
    }

    @Test
    fun `a tag expires 10 s after its creation, however long the coroutine runs`() {
        val root = Root()
        tags.created(root, click, Kinds.CLICK)
        now = 9_999
        tags.sweep()
        assertEquals(1, tags.live())
        now = 10_000
        tags.sweep()
        assertEquals(0, tags.live())
        tags.resumed(Frame(root))
        assertNull(tags.current())
        assertEquals(listOf(true, false), liveChanges)
    }

    @Test
    fun `a child created after its parent's tag expired is not tagged`() {
        val parent = Root()
        tags.created(parent, click, Kinds.CLICK)
        tags.resumed(Frame(parent))
        now = 10_000
        tags.sweep()
        tags.created(Root(), null, null)
        assertEquals(0, tags.live())
    }

    @Test
    fun `a new click while tags are live does not ask for the hooks again`() {
        tags.created(Root(), click, Kinds.CLICK)
        tags.created(Root(), click.copy(from = "7:2"), Kinds.CLICK)
        assertEquals(listOf(true), liveChanges)
        assertEquals(2, tags.live())
    }

    @Test
    fun `with no tags a resume does nothing`() {
        tags.resumed(Frame(Root()))
        assertNull(tags.current())
        assertEquals(0, tags.live())
    }

    @Test
    fun `a declared scope is not carried into coroutines`() {
        val declared = CauseLink("9:1", Basis.DECLARED, CauseRules.APP_SCOPE)
        tags.created(Root(), declared, "app")
        assertEquals(0, tags.live())
    }
}
