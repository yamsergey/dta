package io.yamsergey.dta.daemon.web

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 060: DevTools Network events become the app recorder's steps. */
class CdpTranslatorTest {
    private val t = CdpTranslator("p1", Web.WEBVIEW) { 7 }
    private fun o(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun request(id: String, url: String, extra: String = "") = o(
        """{"requestId":"$id","request":{"url":"$url","method":"POST","headers":{"Accept":"*/*","X-Two":"a\nb"}$extra},"timestamp":100.0,"wallTime":1700000000.0,"type":"Fetch"}""",
    )

    @Test
    fun `a request, its response and its body`() {
        val start = t.event("Network.requestWillBeSent", request("9", "http://h/q", ""","postData":"{\"q\":1}""""))
        assertEquals(listOf(WebStep.HTTP_START, WebStep.HTTP_REQUEST_BODY), start.steps.map { it.op })
        val s = start.steps.first()
        assertEquals("p1:9", s.key)
        assertEquals(1_700_000_000_000, s.timeMs)
        assertEquals(listOf(Header("Accept", "*/*"), Header("X-Two", "a"), Header("X-Two", "b")), s.headers)
        assertEquals(Web.WEBVIEW, s.library)

        val res = t.event("Network.responseReceived", o("""{"requestId":"9","timestamp":100.2,"response":{"status":201,"mimeType":"application/json","protocol":"http/1.1","headers":{"Content-Type":"application/json; charset=utf-8"}}}"""))
        assertEquals(201, res.steps.single().status)

        val done = t.event("Network.loadingFinished", o("""{"requestId":"9","timestamp":100.25,"encodedDataLength":12}"""))
        assertTrue(done.steps.isEmpty())
        assertEquals("9", done.fetchBody)

        val body = t.body("9", "{\"ok\":true}", base64 = false)
        val b = body.single()
        assertEquals(WebStep.HTTP_RESPONSE_BODY, b.op)
        assertEquals("application/json; charset=utf-8", b.contentType)
        assertEquals(1_700_000_000_250, b.endMs)
        assertEquals("{\"ok\":true}", b.text)
    }

    @Test
    fun `a body that cannot be read still completes the request`() {
        t.event("Network.requestWillBeSent", request("1", "http://h/a"))
        t.event("Network.responseReceived", o("""{"requestId":"1","timestamp":100.5,"response":{"status":200,"headers":{}}}"""))
        assertEquals("1", t.event("Network.loadingFinished", o("""{"requestId":"1","timestamp":101.0}""")).fetchBody)
        assertEquals(WebStep.HTTP_COMPLETE, t.body("1", null, false).single().op)
        // Finished with no response seen (served from memory): complete at once, nothing to fetch.
        t.event("Network.requestWillBeSent", request("4", "http://h/b"))
        assertEquals(WebStep.HTTP_COMPLETE, t.event("Network.loadingFinished", o("""{"requestId":"4","timestamp":101.0}""")).steps.single().op)
    }

    @Test
    fun `a failure, a redirect and a data url`() {
        t.event("Network.requestWillBeSent", request("2", "http://h/old"))
        val redirected = t.event("Network.requestWillBeSent", o(
            """{"requestId":"2","request":{"url":"http://h/new","method":"GET","headers":{}},"timestamp":100.1,"wallTime":1700000000.1,"redirectResponse":{"status":302,"headers":{"Location":"/new"}}}""",
        ))
        assertEquals(listOf(WebStep.HTTP_RESPONSE, WebStep.HTTP_COMPLETE, WebStep.HTTP_START), redirected.steps.map { it.op })
        assertEquals("p1:2", redirected.steps[0].key)
        assertEquals("p1:2#1", redirected.steps[2].key)
        val failed = t.event("Network.loadingFailed", o("""{"requestId":"2","timestamp":100.3,"errorText":"net::ERR_FAILED"}"""))
        assertEquals("p1:2#1", failed.steps.single().key)
        assertEquals("net::ERR_FAILED", failed.steps.single().error)

        assertTrue(t.event("Network.requestWillBeSent", request("3", "data:image/png;base64,AAA")).steps.isEmpty())
        assertTrue(t.event("Network.loadingFinished", o("""{"requestId":"3","timestamp":1.0}""")).let { it.steps.isEmpty() && it.fetchBody == null })
        assertEquals(0, t.missed)
    }

    @Test
    fun `responses of requests sent before DTA attached are counted as missed`() {
        t.event("Network.responseReceived", o("""{"requestId":"77","timestamp":1.0,"response":{"status":200,"headers":{}}}"""))
        t.event("Network.loadingFinished", o("""{"requestId":"77","timestamp":1.1}"""))
        assertEquals(1, t.missed)
    }

    @Test
    fun `a socket with its handshake, frames and close`() {
        assertTrue(t.event("Network.webSocketCreated", o("""{"requestId":"w","url":"ws://h/s"}""")).steps.isEmpty())
        val start = t.event("Network.webSocketWillSendHandshakeRequest", o("""{"requestId":"w","timestamp":200.0,"wallTime":1700000100.0,"request":{"headers":{"Upgrade":"websocket"}}}"""))
        assertEquals(WebStep.WS_START, start.steps.single().op)
        assertEquals("ws://h/s", start.steps.single().url)
        assertEquals(WebStep.WS_OPEN, t.event("Network.webSocketHandshakeResponseReceived", o("""{"requestId":"w","timestamp":200.1,"response":{"status":101,"headers":{"Upgrade":"websocket"}}}""")).steps.single().op)
        val sent = t.event("Network.webSocketFrameSent", o("""{"requestId":"w","timestamp":200.2,"response":{"opcode":1,"mask":true,"payloadData":"hi"}}""")).steps.single()
        assertEquals(Direction.OUT, sent.direction)
        assertEquals(MessageOpcode.TEXT, sent.opcode)
        assertEquals("hi", sent.text)
        val got = t.event("Network.webSocketFrameReceived", o("""{"requestId":"w","timestamp":200.3,"response":{"opcode":2,"mask":false,"payloadData":"AQI="}}""")).steps.single()
        assertEquals(MessageOpcode.BINARY, got.opcode)
        assertEquals("AQI=", got.base64)
        assertNull(got.text)
        assertEquals(WebStep.WS_CLOSED, t.event("Network.webSocketClosed", o("""{"requestId":"w","timestamp":201.0}""")).steps.single().op)
    }

    @Test
    fun `frames of a socket opened before DTA attached start it first`() {
        val steps = t.event("Network.webSocketFrameReceived", o("""{"requestId":"x","timestamp":5.0,"response":{"opcode":1,"payloadData":"tick"}}""")).steps
        assertEquals(listOf(WebStep.WS_START, WebStep.WS_OPEN, WebStep.WS_MESSAGE), steps.map { it.op })
        assertEquals(1, t.missed)
    }
}

class TabMatchTest {
    @Test
    fun `the same page ignores case of scheme and host, a trailing slash and the fragment`() {
        assertTrue(TabMatch.same("HTTP://Example.com/a/?x=1#top", "http://example.com/a?x=1"))
        assertTrue(TabMatch.same("https://example.com", "https://example.com/"))
        kotlin.test.assertFalse(TabMatch.same("https://example.com/a", "https://example.com/b"))
        kotlin.test.assertFalse(TabMatch.same("https://example.com/a?x=1", "https://example.com/a?x=2"))
    }

    @Test
    fun `a launch counts for a minute`() {
        val launch = io.yamsergey.dta.protocol.web.WebLaunch("http://h/", 100_000)
        assertTrue(TabMatch.recent(launch, 130_000))
        kotlin.test.assertFalse(TabMatch.recent(launch, 200_000))
    }
}
