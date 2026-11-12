package io.yamsergey.dta.daemon.web

import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 060: which targets are followed, held, let go; what reaches the app. */
class BrowserSessionTest {
    private class FakeCdp : CdpChannel {
        override val events = Channel<CdpEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<Triple<String, String?, JsonObject>>()
        override suspend fun send(method: String, params: JsonObject, sessionId: String?): JsonObject {
            sent += Triple(method, sessionId, params)
            return when (method) {
                "Network.getResponseBody" -> o("""{"body":"hello","base64Encoded":false}""")
                else -> JsonObject(emptyMap())
            }
        }
        override fun close() { events.close() }
        fun event(method: String, params: String, session: String? = null) = events.trySend(CdpEvent(method, o(params), session))
        fun methods(session: String) = sent.filter { it.second == session }.map { it.first }
        fun detached() = sent.filter { it.first == "Target.detachFromTarget" }.map { (it.third["sessionId"] as kotlinx.serialization.json.JsonPrimitive).content }
    }

    private companion object {
        fun o(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
        fun attach(session: String, url: String, waiting: Boolean, type: String = "page") =
            """{"sessionId":"$session","waitingForDebugger":$waiting,"targetInfo":{"targetId":"T$session","type":"$type","url":"$url"}}"""
        fun doc(id: String, url: String) =
            """{"requestId":"$id","type":"Document","request":{"url":"$url","method":"GET","headers":{}},"timestamp":1.0,"wallTime":1700000000.0}"""
    }

    private fun session(cdp: FakeCdp, source: String, ours: (suspend (String) -> Boolean)? = null): Pair<BrowserSession, MutableList<WebStep>> {
        val got = mutableListOf<WebStep>()
        return BrowserSession(cdp, source, { got += it }, ours) to got
    }

    private fun run(cdp: FakeCdp, s: BrowserSession, script: suspend () -> Unit) = runBlocking {
        withTimeout(5_000) {
            val job = launch { s.run() }
            script()
            repeat(20) { yield() }
            cdp.close()
            job.join()
        }
    }

    @Test
    fun `a new WebView page is held until the network is on, then followed whole`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.WEBVIEW)
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("A", "about:blank", waiting = true))
            repeat(5) { yield() }
            cdp.event("Network.requestWillBeSent", doc("1", "http://h/news"), "A")
            cdp.event("Network.responseReceived", """{"requestId":"1","timestamp":1.1,"response":{"status":200,"headers":{"Content-Type":"text/html"}}}""", "A")
            cdp.event("Network.loadingFinished", """{"requestId":"1","timestamp":1.2}""", "A")
        }
        assertEquals(listOf("Network.enable", "Target.setAutoAttach", "Runtime.runIfWaitingForDebugger", "Network.getResponseBody"), cdp.methods("A"))
        assertEquals(listOf(WebStep.HTTP_START, WebStep.HTTP_RESPONSE, WebStep.HTTP_RESPONSE_BODY), got.map { it.op })
        assertEquals("hello", got.last().text)
        val page = s.pages().single()
        assertFalse(page.partial)
        assertEquals(1, page.requests)
        assertEquals("http://h/news", page.url)
    }

    @Test
    fun `a page that existed before attach is partial until it is reloaded`() {
        val cdp = FakeCdp()
        val (s, _) = session(cdp, Web.WEBVIEW)
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("B", "http://h/old", waiting = false))
            cdp.event("Network.webSocketFrameReceived", """{"requestId":"old","timestamp":1.0,"response":{"opcode":1,"payloadData":"tick"}}""", "B")
            repeat(10) { yield() }
            assertTrue(s.pages().single().partial)
            assertTrue(s.reload("TB"))
            assertFalse(s.pages().single().partial)
        }
        assertEquals(listOf("Network.enable", "Target.setAutoAttach", "Page.reload"), cdp.methods("B"))
    }

    @Test
    fun `a tab whose first page is not the app's is let go and nothing of it is kept`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.CUSTOM_TAB) { url -> url == "http://h/ours" }
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("U", "", waiting = true))
            repeat(5) { yield() }
            cdp.event("Network.requestWillBeSent", doc("1", "https://private.example/inbox"), "U")
            repeat(5) { yield() }
            cdp.event("Network.requestWillBeSent", doc("2", "https://private.example/next"), "U")
        }
        assertTrue(got.isEmpty())
        assertEquals(listOf("U"), cdp.detached())
        assertTrue(s.pages().isEmpty())
        assertFalse(s.toString().contains("private"))
    }

    @Test
    fun `the app's tab is kept with what it did while held`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.CUSTOM_TAB) { url -> url == "http://h/ours" }
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("O", "", waiting = true))
            repeat(5) { yield() }
            cdp.event("Network.requestWillBeSent", doc("1", "http://h/ours"), "O")
        }
        assertEquals(listOf(WebStep.HTTP_START), got.map { it.op })
        assertEquals(Web.CUSTOM_TAB, got.single().library)
        assertTrue(cdp.detached().isEmpty())
    }

    @Test
    fun `existing browser tabs are let go unless they show the app's page`() {
        val cdp = FakeCdp()
        val (s, _) = session(cdp, Web.CUSTOM_TAB) { url -> url == "http://h/ours" }
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("X", "https://private.example/", waiting = false))
            cdp.event("Target.attachedToTarget", attach("Y", "http://h/ours", waiting = false))
        }
        assertEquals(listOf("X"), cdp.detached())
        assertTrue(s.pages().single().partial)
    }

    @Test
    fun `a WebView page's frames and workers are followed as the page's, not listed as pages (061)`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.WEBVIEW)
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("A", "http://h/news", waiting = true))
            repeat(5) { yield() }
            cdp.event("Target.attachedToTarget", attach("F", "http://other/frame", waiting = true, type = "iframe"), "A")
            cdp.event("Target.attachedToTarget", attach("W", "http://h/worker.js", waiting = true, type = "worker"), "A")
            repeat(10) { yield() }
            cdp.event("Network.requestWillBeSent", doc("9", "http://other/frame"), "F")
            cdp.event("Network.requestWillBeSent", """{"requestId":"w1","type":"Fetch","request":{"url":"http://h/data","method":"GET","headers":{}},"timestamp":1.0,"wallTime":1700000000.0}""", "W")
        }
        assertEquals(listOf("Network.enable", "Target.setAutoAttach", "Runtime.runIfWaitingForDebugger"), cdp.methods("F"))
        assertEquals(listOf("Network.enable", "Target.setAutoAttach", "Runtime.runIfWaitingForDebugger"), cdp.methods("W"))
        assertEquals(listOf("http://other/frame", "http://h/data"), got.filter { it.op == WebStep.HTTP_START }.map { it.url })
        assertEquals(listOf("TA"), s.pages().map { it.id })
        assertTrue(cdp.detached().isEmpty())
    }

    @Test
    fun `a WebView's service workers are followed (061)`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.WEBVIEW)
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("S", "http://h/sw.js", waiting = true, type = "service_worker"))
            repeat(10) { yield() }
            cdp.event("Network.requestWillBeSent", """{"requestId":"s1","type":"Fetch","request":{"url":"http://h/quote","method":"GET","headers":{}},"timestamp":1.0,"wallTime":1700000000.0}""", "S")
        }
        assertEquals(listOf("Network.enable", "Target.setAutoAttach", "Runtime.runIfWaitingForDebugger"), cdp.methods("S"))
        assertEquals(listOf("http://h/quote"), got.map { it.url })
        assertTrue(s.pages().isEmpty())
    }

    @Test
    fun `in a browser only the app's tab's service worker is followed (061)`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.CUSTOM_TAB) { url -> url == "http://h/ours" }
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("O", "http://h/ours", waiting = false))
            repeat(5) { yield() }
            cdp.event("Target.attachedToTarget", attach("S1", "http://h/sw.js", waiting = true, type = "service_worker"))
            cdp.event("Target.attachedToTarget", attach("S2", "https://private.example/sw.js", waiting = true, type = "service_worker"))
            repeat(10) { yield() }
            cdp.event("Network.requestWillBeSent", """{"requestId":"p","type":"Fetch","request":{"url":"https://private.example/inbox","method":"GET","headers":{}},"timestamp":1.0,"wallTime":1700000000.0}""", "S2")
        }
        assertEquals(listOf("S2"), cdp.detached())
        assertEquals(listOf("Runtime.runIfWaitingForDebugger"), cdp.methods("S2"))
        assertTrue(got.none { it.url?.contains("private") == true })
        assertEquals(listOf("Network.enable", "Target.setAutoAttach", "Runtime.runIfWaitingForDebugger"), cdp.methods("S1"))
    }

    @Test
    fun `a held tab's frames wait with it and go with it (061)`() {
        val cdp = FakeCdp()
        val (s, got) = session(cdp, Web.CUSTOM_TAB) { url -> url == "http://h/ours" }
        run(cdp, s) {
            cdp.event("Target.attachedToTarget", attach("U", "", waiting = true))
            repeat(5) { yield() }
            cdp.event("Target.attachedToTarget", attach("F", "https://ads.example/frame", waiting = true, type = "iframe"), "U")
            repeat(10) { yield() }
            cdp.event("Network.requestWillBeSent", doc("f1", "https://ads.example/frame"), "F")
            repeat(5) { yield() }
            cdp.event("Network.requestWillBeSent", doc("1", "https://private.example/inbox"), "U")
            repeat(5) { yield() }
            cdp.event("Network.requestWillBeSent", doc("f2", "https://ads.example/next"), "F")
        }
        assertTrue(got.isEmpty())
        assertEquals(listOf("U"), cdp.detached())
        assertTrue(s.pages().isEmpty())
    }

    @Test
    fun `a child of a target not followed is let go (061)`() {
        val cdp = FakeCdp()
        val (s, _) = session(cdp, Web.WEBVIEW)
        run(cdp, s) { cdp.event("Target.attachedToTarget", attach("C", "http://h/w.js", waiting = true, type = "worker"), "nobody") }
        assertEquals(listOf("Runtime.runIfWaitingForDebugger"), cdp.methods("C"))
        assertEquals(listOf("C"), cdp.detached())
    }

    @Test
    fun `other targets are resumed and let go`() {
        val cdp = FakeCdp()
        val (s, _) = session(cdp, Web.WEBVIEW)
        run(cdp, s) { cdp.event("Target.attachedToTarget", attach("W", "", waiting = true, type = "auction_worklet")) }
        assertEquals(listOf("Runtime.runIfWaitingForDebugger"), cdp.methods("W"))
        assertEquals(listOf("W"), cdp.detached())
    }
}
