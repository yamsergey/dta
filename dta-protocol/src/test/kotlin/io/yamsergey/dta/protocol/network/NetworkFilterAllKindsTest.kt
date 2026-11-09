package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 051: evaluating the WebSocket and HTTP filters. */
class NetworkFilterAllKindsTest {
    private fun body(part: BodyPart, bytes: Long = 10, type: String? = null, binary: Boolean = false) =
        BodyInfo(part, storedBytes = bytes, contentType = type, binary = binary, completeness = Completeness.COMPLETE)

    private val get = HttpEntry("a:1", "a", 100, "okhttp3", method = "GET", url = "https://api.example.test/q", state = HttpState.COMPLETE, status = 200,
        durationMs = 1500, requestHeaders = listOf(Header("Authorization", "Bearer x")), responseHeaders = listOf(Header("Content-Encoding", "gzip")),
        responseBody = body(BodyPart.RESPONSE, 2048, "application/json; charset=utf-8"))
    private val post = HttpEntry("a:2", "a", 200, "okhttp3", method = "POST", url = "https://api.example.test/o", state = HttpState.COMPLETE, status = 201,
        durationMs = 120, requestBody = body(BodyPart.REQUEST, 100, "application/json"), responseBody = body(BodyPart.RESPONSE, 10, "text/plain"))
    private val open = WsConnectionEntry("a:3", "a", 300, "okhttp3-ws", url = "wss://push.example.test/s", state = WsState.OPEN,
        handshakeHeaders = listOf(Header("Sec-WebSocket-Protocol", "graphql-ws")))
    private val closed = WsConnectionEntry("a:4", "a", 310, "okhttp3-ws", url = "wss://other.example.test/s", state = WsState.CLOSED, closeCode = 1006)
    private val failedWs = WsConnectionEntry("a:5", "a", 320, "okhttp3-ws", url = "wss://down.example.test/s", state = WsState.FAILED)
    private val connecting = WsConnectionEntry("a:6", "a", 330, "okhttp3-ws", url = "wss://slow.example.test/s", state = WsState.CONNECTING)
    private val tick = WsMessageEntry("a:7", "a", 400, "okhttp3-ws", connectionId = open.id, direction = Direction.IN, opcode = MessageOpcode.TEXT,
        payload = body(BodyPart.MESSAGE, 40, "text/plain"))
    private val sub = WsMessageEntry("a:8", "a", 410, "okhttp3-ws", connectionId = open.id, direction = Direction.OUT, opcode = MessageOpcode.TEXT,
        payload = body(BodyPart.MESSAGE, 30))
    private val bin = WsMessageEntry("a:9", "a", 420, "okhttp3-ws", connectionId = closed.id, direction = Direction.IN, opcode = MessageOpcode.BINARY,
        payload = body(BodyPart.MESSAGE, 5000, binary = true))
    private val failedHttp = HttpEntry("a:10", "a", 500, "okhttp3", method = "GET", url = "https://api.example.test/x", state = HttpState.FAILED)

    private val all = listOf(get, post, open, closed, failedWs, connecting, tick, sub, bin, failedHttp)

    private val texts = mapOf(
        (tick.id to BodyPart.MESSAGE) to """{"type":"order.update","channel":"quotes.EUR","id":1}""",
        (sub.id to BodyPart.MESSAGE) to """{"type":"subscribe","channel":"quotes.EUR"}""",
        (post.id to BodyPart.REQUEST) to """{"Token":"t-1"}""",
        (post.id to BodyPart.RESPONSE) to "created",
        (get.id to BodyPart.RESPONSE) to """{"token":"t-2"}""",
    )

    private fun ids(f: NetworkFilter): List<String> {
        val e = NetworkFilterEval(
            filter = f,
            liveRunId = "a",
            connectionUrl = { id -> (all.single { it.id == id } as WsConnectionEntry).url },
            bodyBytes = { entry, part -> texts[entry.id to part]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
            envelope = { m -> texts[m.id to BodyPart.MESSAGE]?.let { WsFormats.detect(it) } },
        )
        return all.filter { e.matches(it) != null }.map { it.id }
    }

    @Test
    fun `direction and frame keep messages only`() {
        assertEquals(listOf(tick.id, bin.id), ids(NetworkFilter(direction = Direction.IN)))
        assertEquals(listOf(sub.id), ids(NetworkFilter(direction = Direction.OUT)))
        assertEquals(listOf(bin.id), ids(NetworkFilter(frame = MessageOpcode.BINARY)))
        assertEquals(listOf(tick.id, sub.id), ids(NetworkFilter(frame = MessageOpcode.TEXT)))
    }

    @Test
    fun `type and topic read the message envelope`() {
        assertEquals(listOf(tick.id), ids(NetworkFilter(envelopeType = "order")))
        assertEquals(listOf(tick.id, sub.id), ids(NetworkFilter(topic = "quotes")))
        assertEquals(emptyList(), ids(NetworkFilter(envelopeType = "nothing")))
    }

    @Test
    fun `connection keeps the socket and its messages`() {
        assertEquals(listOf(open.id, tick.id, sub.id), ids(NetworkFilter(connection = open.id)))
    }

    @Test
    fun `status covers connections`() {
        assertEquals(listOf(open.id), ids(NetworkFilter(status = "open")))
        assertEquals(listOf(closed.id), ids(NetworkFilter(status = "closed")))
        assertEquals(listOf(closed.id), ids(NetworkFilter(status = "1006")))
        assertEquals(listOf(failedWs.id, failedHttp.id), ids(NetworkFilter(status = "failed")))
        assertEquals(listOf(connecting.id), ids(NetworkFilter(status = "connecting")))
        assertEquals(listOf(connecting.id), ids(NetworkFilter(status = "pending")))
        assertEquals(listOf(get.id), ids(NetworkFilter(status = "200")))
    }

    @Test
    fun `header by name or name and value, case ignored, handshake too`() {
        assertEquals(listOf(get.id), ids(NetworkFilter(headerName = "authorization")))
        assertEquals(listOf(get.id), ids(NetworkFilter(headerName = "content-encoding", headerValue = "GZIP")))
        assertEquals(emptyList(), ids(NetworkFilter(headerName = "content-encoding", headerValue = "br")))
        assertEquals(listOf(open.id), ids(NetworkFilter(headerName = "sec-websocket-protocol", headerValue = "graphql")))
    }

    @Test
    fun `mime, duration and size`() {
        assertEquals(listOf(get.id, post.id), ids(NetworkFilter(mime = "json")))
        assertEquals(listOf(post.id, tick.id), ids(NetworkFilter(mime = "text/plain")))
        assertEquals(listOf(get.id), ids(NetworkFilter(minDurationMs = 1000)))
        assertEquals(listOf(post.id), ids(NetworkFilter(maxDurationMs = 500)))
        assertEquals(listOf(get.id, bin.id), ids(NetworkFilter(minSize = 1025)))
        assertEquals(listOf(post.id), ids(NetworkFilter(minSize = 100, maxSize = 200)))
    }

    @Test
    fun `request and response bodies apart, case ignored`() {
        assertEquals(listOf(post.id), ids(NetworkFilter(requestContains = "token")))
        assertEquals(listOf(get.id), ids(NetworkFilter(responseContains = "TOKEN")))
        assertEquals(listOf(post.id, get.id).sorted(), ids(NetworkFilter(bodyContains = "token")).sorted())
    }
}
