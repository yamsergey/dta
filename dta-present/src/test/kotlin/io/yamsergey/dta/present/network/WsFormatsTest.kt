package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WsFormatsTest {
    private fun e(text: String) = WsFormats.detect(text)

    @Test
    fun `a JSON envelope gives its kind, id and topic`() {
        val tick = e("""{"type":"tick","symbol":"ACME","price":20.5}""")!!
        assertEquals(listOf("json", "tick", null, "ACME"), listOf(tick.format, tick.kind, tick.id, tick.topic))
        val req = e("""{"action":"subscribe","requestId":"r-7","channel":"quotes","data":{"symbols":["A"]}}""")!!
        assertEquals(listOf("subscribe", "r-7", "quotes"), listOf(req.kind, req.id, req.topic))
        // A cut preview (256 chars) still says what it is.
        val cut = e("""{"event":"orderbook","id":12,"bids":[[1,2],[3,""")!!
        assertEquals(listOf("orderbook", "12"), listOf(cut.kind, cut.id))
        // Nested keys do not win over top-level ones.
        assertEquals("update", e("""{"data":{"type":"inner"},"type":"update"}""")!!.kind)
    }

    @Test
    fun `JSON-RPC and graphql-ws are named`() {
        val rpc = e("""{"jsonrpc":"2.0","method":"eth_subscribe","id":1,"params":["newHeads"]}""")!!
        assertEquals(listOf("json-rpc", "eth_subscribe", "1"), listOf(rpc.format, rpc.kind, rpc.id))
        val reply = e("""{"jsonrpc":"2.0","id":1,"result":"0xabc"}""")!!
        assertEquals(listOf("json-rpc", "result", "1"), listOf(reply.format, reply.kind, reply.id))
        val gql = e("""{"id":"3","type":"next","payload":{"data":{"price":1}}}""")!!
        assertEquals(listOf("graphql-ws", "next", "3"), listOf(gql.format, gql.kind, gql.id))
    }

    @Test
    fun `a name then JSON is split, on one line or two`() {
        val a = e("""orderUpdate {"id":7,"status":"filled"}""")!!
        assertEquals(listOf("prefix+json", "orderUpdate", "7"), listOf(a.format, a.kind, a.id))
        assertEquals("""{"id":7,"status":"filled"}""", a.payload)
        val b = e("messageId\n{\"x\":1}")!!
        assertEquals(listOf("prefix+json", "messageId"), listOf(b.format, b.kind))
    }

    @Test
    fun `Socket IO, Phoenix, STOMP and SignalR`() {
        val sio = e("""42["chat message",{"text":"hi"}]""")!!
        assertEquals(listOf("socket.io", "chat message"), listOf(sio.format, sio.kind))
        val ack = e("""431["ok",{}]""")!!
        assertEquals("1", ack.id)
        assertEquals("ping", e("2")!!.kind)
        val phx = e("""["3","7","room:lobby","new_msg",{"body":"hi"}]""")!!
        assertEquals(listOf("phoenix", "new_msg", "7", "room:lobby"), listOf(phx.format, phx.kind, phx.id, phx.topic))
        val stomp = e("MESSAGE\ndestination:/topic/quotes\nmessage-id:m-42\ncontent-type:application/json\n\n{\"p\":1}\u0000")!!
        assertEquals(listOf("stomp", "MESSAGE", "m-42", "/topic/quotes"), listOf(stomp.format, stomp.kind, stomp.id, stomp.topic))
        assertEquals("{\"p\":1}", stomp.payload)
        val sr = e("{\"type\":1,\"target\":\"ReceiveMessage\",\"invocationId\":\"5\",\"arguments\":[\"hi\"]}\u001e")!!
        assertEquals(listOf("signalr", "ReceiveMessage", "5"), listOf(sr.format, sr.kind, sr.id))
    }

    @Test
    fun `plain text and other shapes are not guessed at`() {
        assertNull(e("hello there"))
        assertNull(e("42 is the answer"))
        assertNull(e(""))
    }
}

class WsMessageBodyTest {
    @Test
    fun `a message's envelope is in the header and its JSON payload is the tree (038)`() {
        val result = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"body":{"part":"message","storedBytes":60},"text":${kotlinx.serialization.json.JsonPrimitive("MESSAGE\ndestination:/topic/quotes\nmessage-id:m-1\n\n{\"p\":1,\"s\":\"ACME\"}\u0000")}}""",
        ) as kotlinx.serialization.json.JsonObject
        val v = BodyView.of(result)
        assertEquals(true, v.header.line.endsWith("stomp MESSAGE #m-1 @/topic/quotes"), v.header.line)
        assertEquals(true, BodyMode.TREE in v.modes)
        assertEquals(listOf("p", "s"), v.tree()!!.children.map { it.key })
        assertEquals(true, v.render(BodyMode.FORMATTED).startsWith("{\n  \"p\": 1"), v.render(BodyMode.FORMATTED))
        assertEquals(true, v.render(BodyMode.RAW).startsWith("MESSAGE\n"))
    }
}
