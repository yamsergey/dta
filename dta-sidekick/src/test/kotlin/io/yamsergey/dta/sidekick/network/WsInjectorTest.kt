package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.InjectionDelivery
import io.yamsergey.dta.protocol.network.InjectionSource
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsInjection
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.sidekick.util.ManualScheduler
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 020 (US1): pushes reach the app in order, on the reading thread when they can, never silently. */
class WsInjectorTest {

    private class FakeDelivery(var canPing: Boolean = true) : WsDelivery {
        var alive = true
        val got = mutableListOf<String>()
        var pings = 0
        var throwOnDeliver: Throwable? = null
        val deliveringFlag = mutableListOf<Boolean>()
        override fun alive() = alive
        override fun deliver(message: PushMessage) {
            deliveringFlag += WsPushGuard.delivering
            throwOnDeliver?.let { throw it }
            got += message.text ?: "bytes:" + message.bytes!!.joinToString(",")
        }
        override fun ping(): Boolean = canPing.also { if (it) pings++ }
    }

    private class FakeRecorder : PushRecorder {
        override val runId = "r2-7"
        val connections = mutableMapOf<String, WsConnectionEntry>()
        val recorded = mutableListOf<Triple<String, PushMessage, WsInjection>>()
        override fun connection(id: String) = connections[id]
        override fun record(connectionId: String, message: PushMessage, injection: WsInjection): String {
            recorded += Triple(connectionId, message, injection)
            return "r2-7:${100 + recorded.size}"
        }
        fun open(id: String, url: String = "ws://h/$id", runId: String = this.runId, state: WsState = WsState.OPEN) {
            connections[id] = WsConnectionEntry(id, runId, 0, "okhttp3-ws", url = url, state = state)
        }
    }

    private val scheduler = ManualScheduler()
    private val recorder = FakeRecorder()
    private val injector = WsInjector(recorder, scheduler)
    private val client = PushSource(InjectionSource.CLIENT)

    private fun text(t: String) = PushMessage(t, null)

    private fun attached(id: String = "r2-7:1", delivery: FakeDelivery = FakeDelivery()): FakeDelivery {
        recorder.open(id)
        injector.attach(id, delivery)
        return delivery
    }

    @Test
    fun `a frame flushes queued pushes in order on the reading thread`() {
        val socket = attached()
        val first = injector.push("r2-7:1", text("a"), client, waitMs = 1000)
        val second = injector.push("r2-7:1", text("b"), client, waitMs = 1000)
        assertEquals(2, socket.pings, "each push asks the server for a frame")
        assertTrue(socket.got.isEmpty(), "nothing until a frame arrives")
        scheduler.advance(40)
        injector.flush("r2-7:1")
        assertEquals(listOf("a", "b"), socket.got)
        assertEquals(listOf(true, true), socket.deliveringFlag, "receive hooks pass DTA's delivery through, on the reading thread too")
        val outcome = first.get(0, TimeUnit.MILLISECONDS) as PushOutcome.Delivered
        assertEquals(InjectionDelivery.READING_THREAD, outcome.delivery)
        assertEquals(40, outcome.waitedMs)
        assertEquals("r2-7:101", outcome.entryId)
        assertEquals("r2-7:102", (second.get() as PushOutcome.Delivered).entryId)
        val injection = recorder.recorded[0].third
        assertEquals(WsInjection(InjectionSource.CLIENT, delivery = InjectionDelivery.READING_THREAD, waitedMs = 40), injection)
        scheduler.advance(2000)
        assertEquals(listOf("a", "b"), socket.got, "the deadline does not deliver them again")
    }

    @Test
    fun `without a frame the push is delivered from DTA's thread at the deadline, flagged for the hooks`() {
        val socket = attached(delivery = FakeDelivery(canPing = false))
        val pushed = injector.push("r2-7:1", PushMessage(null, byteArrayOf(1, 2)), client, waitMs = 1000)
        scheduler.advance(999)
        assertTrue(socket.got.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf("bytes:1,2"), socket.got)
        assertEquals(listOf(true), socket.deliveringFlag, "receive hooks pass a DTA-thread delivery through unrecorded")
        assertFalse(WsPushGuard.delivering, "flag cleared after")
        val outcome = pushed.get() as PushOutcome.Delivered
        assertEquals(InjectionDelivery.DTA_THREAD, outcome.delivery)
        assertEquals(1000, outcome.waitedMs)
        assertContentEquals(byteArrayOf(1, 2), recorder.recorded.single().second.bytes)
    }

    @Test
    fun `a deadline delivers everything queued before it, keeping order, and nothing after it`() {
        val socket = attached(delivery = FakeDelivery(canPing = false))
        injector.push("r2-7:1", text("a"), client, waitMs = 1000)
        scheduler.advance(500)
        injector.push("r2-7:1", text("b"), client, waitMs = 1000)
        scheduler.advance(500)
        assertEquals(listOf("a"), socket.got)
        scheduler.advance(500)
        assertEquals(listOf("a", "b"), socket.got)
    }

    @Test
    fun `wait zero delivers now from DTA's thread`() {
        val socket = attached()
        val pushed = injector.push("r2-7:1", text("now"), client, waitMs = 0)
        scheduler.advance(0)
        assertEquals(listOf("now"), socket.got)
        assertEquals(0, socket.pings)
        assertEquals(InjectionDelivery.DTA_THREAD, (pushed.get() as PushOutcome.Delivered).delivery)
    }

    @Test
    fun `the reading thread never waits for DTA's thread`() {
        val socket = attached()
        injector.push("r2-7:1", text("a"), client, waitMs = 1000)
        injector.lockFor("r2-7:1").lock()
        try {
            val reader = Thread { injector.flush("r2-7:1") }
            reader.start()
            reader.join(2000)
            assertFalse(reader.isAlive, "flush returned without the lock")
            assertTrue(socket.got.isEmpty())
        } finally {
            injector.lockFor("r2-7:1").unlock()
        }
        injector.flush("r2-7:1")
        assertEquals(listOf("a"), socket.got)
    }

    @Test
    fun `a connection that closed before delivery fails, nothing recorded`() {
        val socket = attached()
        val pushed = injector.push("r2-7:1", text("late"), client, waitMs = 1000)
        recorder.open("r2-7:1", state = WsState.CLOSED)
        recorder.connections["r2-7:1"] = recorder.connections["r2-7:1"]!!.copy(closeCode = 1000, closeReason = "done")
        injector.flush("r2-7:1")
        val outcome = pushed.get() as PushOutcome.Failed
        assertEquals("connection r2-7:1 closed before the message was delivered (code 1000 done)", outcome.reason)
        assertTrue(socket.got.isEmpty())
        assertTrue(recorder.recorded.isEmpty())
    }

    @Test
    fun `pushes that cannot start say why, in plain words`() {
        recorder.open("r2-7:1", state = WsState.CLOSED)
        recorder.connections["r2-7:1"] = recorder.connections["r2-7:1"]!!.copy(closeCode = 1000, closeReason = "done")
        injector.attach("r2-7:1", FakeDelivery())
        recorder.open("r1-5:3", runId = "r1-5")
        val failures = listOf(
            "r2-7:1" to "connection r2-7:1 is closed (code 1000 done); push to an open connection",
            "r2-7:9" to "r2-7:9 is not a WebSocket connection of the app; push to an open connection (network_list, type ws-connection, state open)",
            "r1-5:3" to "connection r1-5:3 is from an earlier run of the app (r1-5); only connections of the running process can receive pushes",
        )
        for ((id, reason) in failures) {
            val e = assertFailsWith<PushException> { injector.push(id, text("x"), client, waitMs = 0) }
            assertEquals(reason, e.message)
        }
        val collected = attached("r2-7:2")
        collected.alive = false
        assertEquals(
            "the app no longer holds connection r2-7:2 (its socket was garbage-collected without being closed)",
            assertFailsWith<PushException> { injector.push("r2-7:2", text("x"), client, waitMs = 0) }.message,
        )
    }

    @Test
    fun `with no connection named, the only open one is used, otherwise the open ones are listed`() {
        assertEquals("the app has no open WebSocket connection", assertFailsWith<PushException> { injector.push(null, text("x"), client, 0) }.message)
        val only = attached("r2-7:1")
        injector.push(null, text("x"), client, waitMs = 0)
        scheduler.advance(0)
        assertEquals(listOf("x"), only.got)
        attached("r2-7:4")
        val e = assertFailsWith<PushException> { injector.push(null, text("x"), client, 0) }
        assertEquals("the app has 2 open WebSocket connections; name one with connection: r2-7:1 (ws://h/r2-7:1), r2-7:4 (ws://h/r2-7:4)", e.message)
        assertEquals(listOf("r2-7:1", "r2-7:4"), injector.openConnections().map { it.id })
    }

    @Test
    fun `a listener that throws is reported, the message is still recorded`() {
        val socket = attached(delivery = FakeDelivery(canPing = false))
        socket.throwOnDeliver = IllegalStateException("boom")
        val pushed = injector.push("r2-7:1", text("x"), client, waitMs = 0)
        scheduler.advance(0)
        val outcome = pushed.get() as PushOutcome.Delivered
        assertEquals("IllegalStateException: boom", outcome.listenerError)
        assertEquals(1, recorder.recorded.size)
    }

    @Test
    fun `a delayed script push waits, then queues, and its marker names the script and handler`() {
        val socket = attached()
        val failures = mutableListOf<String>()
        injector.push("r2-7:1", text("snapshot:42"), PushSource(InjectionSource.SCRIPT, "3fa2", ScriptHandler.WS_SEND), waitMs = 1000, delayMs = 200) { failures += it }
        scheduler.advance(199)
        assertEquals(0, socket.pings)
        scheduler.advance(1)
        assertEquals(1, socket.pings)
        scheduler.advance(10)
        injector.flush("r2-7:1")
        assertEquals(listOf("snapshot:42"), socket.got)
        val injection = recorder.recorded.single().third
        assertEquals(WsInjection(InjectionSource.SCRIPT, "3fa2", ScriptHandler.WS_SEND, InjectionDelivery.READING_THREAD, 10), injection)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a delayed push to a connection that closed meanwhile reports through its failure callback`() {
        attached()
        val failures = mutableListOf<String>()
        injector.push("r2-7:1", text("x"), PushSource(InjectionSource.SCRIPT, "3fa2"), waitMs = 1000, delayMs = 100) { failures += it }
        recorder.open("r2-7:1", state = WsState.FAILED)
        scheduler.advance(100)
        assertEquals(listOf("connection r2-7:1 failed before the message was delivered"), failures)
    }

    @Test
    fun `flush on a socket with nothing queued does nothing`() {
        val socket = attached()
        injector.flush("r2-7:1")
        injector.flush("unknown")
        assertTrue(socket.got.isEmpty())
        assertNull(recorder.recorded.firstOrNull())
    }
}
