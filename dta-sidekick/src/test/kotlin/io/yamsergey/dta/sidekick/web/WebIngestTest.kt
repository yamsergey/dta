package io.yamsergey.dta.sidekick.web

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebStep
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 060: the daemon's steps become ordinary entries of the app's history. */
class WebIngestTest {
    @TempDir
    lateinit var dir: File

    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7, clock = { 99_000 }) }
    private val recorder by lazy { NetworkRecorder(store, clock = { 99_000 }) { _, _ -> } }
    private val ingest by lazy { WebIngest(recorder) }

    @AfterEach
    fun close() = store.close()

    private fun http() = store.entries().filterIsInstance<HttpEntry>()
    private fun NetworkStore.entries() = list(limit = 100).entries

    @Test
    fun `a request with its bodies is one complete entry timed by the page`() {
        val r = ingest.apply(listOf(
            WebStep(WebStep.HTTP_START, "s:1", library = Web.WEBVIEW, method = "POST", url = "http://h/q", headers = listOf(Header("A", "b")), timeMs = 1_000),
            WebStep(WebStep.HTTP_REQUEST_BODY, "s:1", text = "{\"q\":1}", contentType = "application/json"),
            WebStep(WebStep.HTTP_RESPONSE, "s:1", status = 200, headers = listOf(Header("Content-Type", "application/json")), protocol = "http/1.1"),
            WebStep(WebStep.HTTP_RESPONSE_BODY, "s:1", text = "{\"ok\":true}", contentType = "application/json", endMs = 1_250),
        ))
        assertEquals(4, r.applied)
        val e = http().single()
        assertEquals(Web.WEBVIEW, e.library)
        assertEquals(HttpState.COMPLETE, e.state)
        assertEquals(1_000, e.timeMs)
        assertEquals(250, e.durationMs)
        assertEquals(200, e.status)
        assertEquals(7L, e.requestBody!!.storedBytes)
        assertEquals("{\"ok\":true}", store.openBody(e.id, BodyPart.RESPONSE)!!.readBytes().decodeToString())
        assertTrue(e.initiator!!.missing!!.contains("web page"))
    }

    @Test
    fun `steps for an unknown key are counted, not applied`() {
        val r = ingest.apply(listOf(WebStep(WebStep.HTTP_COMPLETE, "nope", endMs = 5)))
        assertEquals(0, r.applied)
        assertEquals(1, r.unknown)
    }

    @Test
    fun `a failed request and a socket with its messages`() {
        ingest.apply(listOf(
            WebStep(WebStep.HTTP_START, "s:2", library = Web.CUSTOM_TAB, method = "GET", url = "http://h/x", timeMs = 10),
            WebStep(WebStep.HTTP_FAILED, "s:2", error = "net::ERR_CONNECTION_REFUSED", endMs = 30),
            WebStep(WebStep.WS_START, "s:3", library = Web.WEBVIEW, url = "ws://h/s", timeMs = 40),
            WebStep(WebStep.WS_OPEN, "s:3", headers = listOf(Header("Upgrade", "websocket")), timeMs = 45),
            WebStep(WebStep.WS_MESSAGE, "s:3", direction = Direction.OUT, opcode = MessageOpcode.TEXT, text = "hi", timeMs = 50),
            WebStep(WebStep.WS_MESSAGE, "s:3", direction = Direction.IN, opcode = MessageOpcode.BINARY, base64 = "AQI=", timeMs = 51),
            WebStep(WebStep.WS_CLOSED, "s:3", timeMs = 60),
        ))
        val failed = http().single()
        assertEquals(HttpState.FAILED, failed.state)
        assertEquals("net::ERR_CONNECTION_REFUSED", failed.failure)
        val all = store.entries()
        val socket = all.filterIsInstance<WsConnectionEntry>().single()
        assertEquals(WsState.CLOSED, socket.state)
        assertEquals(2, socket.messageCount)
        val messages = all.filterIsInstance<WsMessageEntry>()
        assertEquals(listOf(50L, 51L), messages.map { it.timeMs }.sorted())
        assertEquals(Web.WEBVIEW, messages.first().library)
    }
}
