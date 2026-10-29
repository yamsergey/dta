package io.yamsergey.dta.protocol.wire

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendQueueTest {
    /** A sender like the JDK WebSocket: a second send while one is pending fails. */
    private class StrictSender {
        val started = CopyOnWriteArrayList<CompletableFuture<String>>()
        val order = CopyOnWriteArrayList<String>()
        @Volatile var busy = false

        fun send(text: String): CompletableFuture<String> {
            check(!busy) { "Send pending" }
            busy = true
            order += text
            return CompletableFuture<String>().also { started += it }
        }

        fun finishNext() {
            busy = false
            started.removeAt(0).complete("ok")
        }
    }

    @Test
    fun `a send starts only when the previous one completed, in order`() {
        val sender = StrictSender()
        val queue = SendQueue()
        val futures = (1..3).map { n -> queue.enqueue { sender.send("m$n") } }
        assertEquals(listOf("m1"), sender.order)
        sender.finishNext()
        assertEquals(listOf("m1", "m2"), sender.order)
        sender.finishNext(); sender.finishNext()
        assertTrue(futures.all { it.get() == "ok" })
        assertEquals(listOf("m1", "m2", "m3"), sender.order)
    }

    @Test
    fun `a failed send does not stop the ones after it`() {
        val queue = SendQueue()
        val failed = queue.enqueue { CompletableFuture.failedFuture<String>(IllegalStateException("closed")) }
        val next = queue.enqueue { CompletableFuture.completedFuture("ok") }
        assertTrue(failed.isCompletedExceptionally)
        assertEquals("ok", next.get())
    }

    @Test
    fun `many threads enqueue at once without overlapping sends`() {
        val sender = StrictSender()
        val queue = SendQueue()
        val threads = (1..20).map { n -> thread { queue.enqueue { sender.send("m$n") } } }
        threads.forEach { it.join() }
        repeat(20) { sender.finishNext() }
        assertEquals(20, sender.order.size)
    }
}
