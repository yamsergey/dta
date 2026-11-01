package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.Kinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 016 (T018): what is being handled on the main thread right now, as a cause. */
class HandlingScopeTest {
    private var main = true
    private var now = 0L
    private val scope = HandlingScope(isMain = { main }, clock = { now })

    @Test
    fun `inside a click its cause is exact, outside there is none`() {
        assertNull(scope.current())
        scope.enter("7:1", Kinds.CLICK)
        val cause = scope.current()!!
        assertEquals("7:1", cause.from)
        assertEquals(Basis.EXACT, cause.basis)
        assertEquals("issued-during:click", cause.rule)
        scope.exit()
        assertNull(scope.current())
    }

    @Test
    fun `nested scopes give the innermost, then the outer again`() {
        scope.enter("7:1", Kinds.CLICK)
        scope.enter("7:2", Kinds.BACK)
        assertEquals("7:2", scope.current()!!.from)
        scope.exit()
        assertEquals("7:1", scope.current()!!.from)
    }

    @Test
    fun `an app scope is declared by the app`() {
        scope.enterDeclared("7:5")
        val cause = scope.current()!!
        assertEquals(Basis.DECLARED, cause.basis)
        assertEquals("app-scope", cause.rule)
        assertEquals("app", cause.declaredBy)
    }

    @Test
    fun `other threads see no scope and do not change it`() {
        scope.enter("7:1", Kinds.CLICK)
        main = false
        assertNull(scope.current())
        scope.exit()
        main = true
        assertEquals("7:1", scope.current()!!.from)
    }

    @Test
    fun `an app scope on another thread is that thread's own`() {
        main = false
        scope.enterDeclared("7:5")
        assertEquals("7:5", scope.current()!!.from)
        main = true
        assertNull(scope.current(), "the main thread does not see it")
        main = false
        scope.exit()
        assertNull(scope.current())
    }

    @Test
    fun `a click opens no scope off the main thread`() {
        main = false
        scope.enter("7:1", Kinds.CLICK)
        assertNull(scope.current())
    }

    @Test
    fun `a scope whose exit never came goes stale and is dropped, counted`() {
        scope.enter("7:1", Kinds.CLICK)
        now += HandlingScope.STALE_MS + 1
        assertNull(scope.current())
        assertEquals(1, scope.staleDropped)
        scope.enter("7:2", Kinds.CLICK)
        assertEquals("7:2", scope.current()!!.from)
    }

    @Test
    fun `an exit without an entry changes nothing`() {
        scope.exit()
        assertNull(scope.current())
    }

    @Test
    fun `the time spent in scopes is measured`() {
        scope.enter("7:1", Kinds.CLICK)
        now += 3
        scope.exit()
        assertEquals(1, scope.costs().first)
    }
}
