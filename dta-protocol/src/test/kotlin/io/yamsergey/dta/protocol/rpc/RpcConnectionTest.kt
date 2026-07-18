package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcConnectionTest {

    private class Harness {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val registry = MethodRegistry()
        val rpc = RpcConnection(registry, sendText = { sent += it })

        fun receive(message: Message) = rpc.handleText(DtaJson.encodeToString(Message.serializer(), message))

        fun sentMessages(): List<Message> =
            synchronized(sent) { sent.map { DtaJson.decodeFromString(Message.serializer(), it) } }
    }

    @Test
    fun `known method dispatches and responds with matching id`() {
        val h = Harness()
        h.registry.register("echo") { ctx -> ctx.params ?: JsonPrimitive("empty") }
        h.receive(Request(id = 42, method = "echo", params = buildJsonObject { put("x", 1) }))

        val response = assertIs<Response>(h.sentMessages().single())
        assertEquals(42, response.id)
        assertEquals(buildJsonObject { put("x", 1) }, response.result)
    }

    @Test
    fun `unknown method answers METHOD_NOT_FOUND naming the method`() {
        val h = Harness()
        h.receive(Request(id = 1, method = "does_not_exist"))

        val response = assertIs<Response>(h.sentMessages().single())
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, response.error!!.code)
        assertTrue("does_not_exist" in response.error!!.message)
    }

    @Test
    fun `invalid params surface as INVALID_PARAMS with the complaint`() {
        val h = Harness()
        h.registry.register("blob") { throw InvalidParamsException("bytes must be a positive integer") }
        h.receive(Request(id = 2, method = "blob"))

        val response = assertIs<Response>(h.sentMessages().single())
        assertEquals(ErrorCodes.INVALID_PARAMS, response.error!!.code)
        assertTrue("bytes must be a positive integer" in response.error!!.message)
    }

    @Test
    fun `handler crash answers INTERNAL with a short message and no stack trace`() {
        val h = Harness()
        h.registry.register("boom") { throw IllegalStateException("subsystem not mounted") }
        h.receive(Request(id = 3, method = "boom"))

        val response = assertIs<Response>(h.sentMessages().single())
        assertEquals(ErrorCodes.INTERNAL, response.error!!.code)
        val message = response.error!!.message
        assertTrue("subsystem not mounted" in message)
        assertTrue("\n" !in message && "\tat " !in message, "must not carry a stack trace: $message")
    }

    @Test
    fun `handler crash without message still yields an actionable error`() {
        val h = Harness()
        h.registry.register("boom") { throw NullPointerException() }
        h.receive(Request(id = 4, method = "boom"))

        val response = assertIs<Response>(h.sentMessages().single())
        assertEquals(ErrorCodes.INTERNAL, response.error!!.code)
        assertTrue(response.error!!.message.isNotBlank())
    }

    @Test
    fun `malformed json is ignored without reply or crash`() {
        val h = Harness()
        h.rpc.handleText("""{"type":"nope"}""")
        h.rpc.handleText("not json at all")
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `incoming responses and events are ignored by the server side`() {
        val h = Harness()
        h.receive(Response(id = 1, result = JsonPrimitive(1)))
        h.receive(Event(name = "stray", seq = 1))
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `events carry strictly increasing seq starting at 1 under concurrency`() {
        val h = Harness()
        val threads = 10
        val perThread = 20
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                go.await()
                repeat(perThread) { h.rpc.sendEvent("tick") }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))

        val seqs = h.sentMessages().map { assertIs<Event>(it).seq }.sorted()
        assertEquals((1L..(threads * perThread).toLong()).toList(), seqs)
    }

    @Test
    fun `handler degradation markers surface on the response`() {
        val h = Harness()
        h.registry.register("layout_tree") { ctx ->
            ctx.markDegraded("compose", "inspection disabled: enable-too-late")
            buildJsonObject { put("nodes", 0) }
        }
        h.receive(Request(id = 6, method = "layout_tree"))

        val response = assertIs<Response>(h.sentMessages().single())
        val marker = response.degraded!!.single()
        assertEquals("compose", marker.capability)
        assertTrue("enable-too-late" in marker.reason)
    }

    @Test
    fun `null handler result becomes an empty object result not an error`() {
        val h = Harness()
        h.registry.register("side_effect_only") { null }
        h.receive(Request(id = 5, method = "side_effect_only"))

        val response = assertIs<Response>(h.sentMessages().single())
        assertNull(response.error)
    }
}
