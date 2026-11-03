package io.yamsergey.dta.state

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Feature 016 (T036): the app's own source — nothing without DTA, the bridge's ops with it. */
class DtaStateTest {
    private data class Call(val op: Int, val a: String?, val b: String?, val attrs: List<String>?, val id: Long, val cause: Long)

    private val calls = mutableListOf<Call>()

    init {
        DtaStateBridge.recorder = { op, a, b, attrs, id, cause -> calls += Call(op, a, b, attrs?.toList(), id, cause) }
    }

    @AfterTest
    fun off() {
        DtaStateBridge.on = false
        DtaStateBridge.recorder = null
    }

    @Test
    fun `without DTA nothing is built and calls cost almost nothing`() {
        @Suppress("DEPRECATION") val thread = Thread.currentThread().id
        val attrs = arrayOf("id" to 1) // the caller's own pair: built before the call, not by it
        fun allocated(): Long {
            val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val before = mx.getThreadAllocatedBytes(thread)
            repeat(10_000) {
                DtaState.facet("cart", "items")
                DtaState.event("cart.removed", *attrs)
                DtaState.event("cart.synced", mapOf<String, Any?>())
            }
            return mx.getThreadAllocatedBytes(thread) - before
        }
        // Allocation, not time: a busy CI runner changes the time, never what is built. The spread of
        // `attrs` copies the array (the caller's), 10 000 times; nothing else may be allocated.
        allocated()
        val bytes = (1..3).minOf { allocated() }
        val spread = 10_000L * 32
        assertTrue(bytes <= spread + 4_096, "10 000 calls allocated $bytes bytes (the spread copies alone are about $spread)")
        assertSame(DtaEvent.NONE, DtaState.event("x"))
        assertEquals(42, DtaState.during(DtaEvent.NONE) { 42 })
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `with DTA every call reaches the bridge with short strings`() {
        DtaStateBridge.on = true
        val a = DtaState.event("cart.removed", "id" to 42, "note" to "x".repeat(500))
        val b = DtaState.event("cart.synced", causedBy = a)
        assertNotEquals(a.localId, b.localId)
        assertTrue(a.localId > 0)
        DtaState.cause(a, b)
        assertEquals(7, DtaState.during(a) { 7 })
        runCatching { DtaState.during(a) { error("boom") } }
        DtaState.facet("cart", "empty")
        val ops = calls.map { it.op }
        assertEquals(listOf(2, 2, 3, 4, 5, 4, 5, 1), ops, "event, event, cause, scope ×2 (even when the block throws), facet")
        assertEquals(listOf("id", "42", "note", "x".repeat(120)), calls[0].attrs)
        assertEquals(a.localId, calls[1].cause)
        assertEquals(Call(3, null, null, null, b.localId, a.localId), calls[2])
        assertEquals(Call(1, "cart", "empty", null, 0, 0), calls.last())
    }

    @Test
    fun `attributes are bounded`() {
        DtaStateBridge.on = true
        val many = (1..20).associate { "k$it" to it }
        DtaState.event("x".repeat(100), many)
        assertEquals(16, calls.single().attrs!!.size)
        assertEquals(64, calls.single().a!!.length)
    }
}
