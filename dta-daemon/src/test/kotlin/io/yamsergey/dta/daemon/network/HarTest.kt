package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 026: HAR 1.2 entries as browsers' tools read them. */
class HarTest {
    private fun JsonObject.o(name: String) = this[name]!!.jsonObject
    private fun JsonObject.s(name: String) = this[name]?.jsonPrimitive?.content

    private val post = HttpEntry(
        "r1:1", "r1", 1_793_713_456_000, "okhttp3", method = "POST", url = "https://api.example.com/v1/cart?item=3&q=a%20b", state = HttpState.COMPLETE,
        status = 201, durationMs = 142, protocol = "http/1.1",
        requestHeaders = listOf(Header("Content-Type", "application/json")), responseHeaders = listOf(Header("Content-Type", "application/json")),
        requestBody = BodyInfo(BodyPart.REQUEST, 9, contentType = "application/json", completeness = Completeness.COMPLETE),
        responseBody = BodyInfo(BodyPart.RESPONSE, 4, contentType = "image/png", binary = true, completeness = Completeness.COMPLETE),
    )

    @Test
    fun `an exchange has request, response, timings and bodies`() {
        val e = Har.http(post, Har.Content("application/json", 9, "{\"id\":3}"), Har.Content("image/png", 4, "iVBORw==", base64 = true))
        assertEquals("2026-11-03T13:44:16Z", e.s("startedDateTime"))
        assertEquals("142", e.s("time"))
        val request = e.o("request")
        assertEquals("POST", request.s("method"))
        assertEquals("HTTP/1.1", request.s("httpVersion"))
        assertEquals(listOf("item" to "3", "q" to "a b"), request["queryString"]!!.jsonArray.map { it.jsonObject.s("name")!! to it.jsonObject.s("value")!! })
        assertEquals("{\"id\":3}", request.o("postData").s("text"))
        assertEquals("application/json", request.o("postData").s("mimeType"))
        val response = e.o("response")
        assertEquals("201", response.s("status"))
        assertEquals("base64", response.o("content").s("encoding"))
        assertEquals("iVBORw==", response.o("content").s("text"))
        assertEquals("142", e.o("timings").s("wait"))
        assertEquals("r1:1", e.o("_dta").s("id"))
    }

    @Test
    fun `a failed request has status 0 and its error, a body not stored says why`() {
        val failed = post.copy(state = HttpState.FAILED, status = null, failure = "UnknownHostException: api.example.com", responseBody = null, protocol = null)
        val e = Har.http(failed, Har.Content("application/json", 9, null, comment = "body not stored: dropped"), null)
        assertEquals("0", e.o("response").s("status"))
        assertEquals("UnknownHostException: api.example.com", e.s("_error"))
        assertEquals("body not stored: dropped", e.o("request").o("postData").s("comment"))
        assertEquals("0", e.o("response").o("content").s("size"))
        assertNull(e.o("response").o("content").s("text"))
    }

    @Test
    fun `a WebSocket carries its messages as Chrome does`() {
        val socket = WsConnectionEntry("r1:5", "r1", 1_000_000, "okhttp3-ws", url = "wss://ws.example.com/feed", state = WsState.OPEN, openedAtMs = 1_000_050,
            handshakeHeaders = listOf(Header("Sec-WebSocket-Protocol", "v1")))
        val out = WsMessageEntry("r1:6", "r1", 1_000_100, "okhttp3-ws", connectionId = "r1:5", direction = Direction.OUT, opcode = MessageOpcode.TEXT,
            payload = BodyInfo(BodyPart.MESSAGE, 8, completeness = Completeness.COMPLETE))
        val inn = out.copy(id = "r1:7", timeMs = 1_000_250, direction = Direction.IN, opcode = MessageOpcode.BINARY)
        val e = Har.webSocket(socket, listOf(out to Har.Content(null, 8, "{\"sub\":1}"), inn to Har.Content(null, 2, "AAE=", base64 = true)))
        assertEquals("websocket", e.s("_resourceType"))
        assertEquals("101", e.o("response").s("status"))
        val messages = e["_webSocketMessages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("send", "receive"), messages.map { it.s("type") })
        assertEquals(listOf("1", "2"), messages.map { it.s("opcode") })
        assertEquals("1000.1", messages[0].s("time"))
        assertEquals("AAE=", messages[1].s("data"))
    }

    @Test
    fun `the log names its creator and holds the entries in order`() {
        val log = Har.document(listOf(JsonObject(mapOf("a" to JsonPrimitive(1)))), "0.10.0").o("log")
        assertEquals("1.2", log.s("version"))
        assertEquals("DTA", log.o("creator").s("name"))
        assertEquals("0.10.0", log.o("creator").s("version"))
        assertEquals(1, log["entries"]!!.jsonArray.size)
    }
}
