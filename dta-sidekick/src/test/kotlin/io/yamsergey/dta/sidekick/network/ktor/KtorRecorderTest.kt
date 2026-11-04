package io.yamsergey.dta.sidekick.network.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameType
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 034: what the hooks record, given the objects a real Ktor client builds (MockEngine). */
class KtorRecorderTest {
    @TempDir lateinit var dir: File
    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7, clock = { 1_000L }) }
    private val recorder by lazy { NetworkRecorder(store, clock = { 1_000L }) { _, _ -> } }
    private val ktor by lazy { KtorRecorder(recorder) { null } }

    @AfterEach fun close() = store.close()

    private val client = HttpClient(MockEngine { req ->
        respond("""{"places":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    /** Plays the two hook calls Ktor makes for one call: the original call's setResponse, then the saved call's. */
    private fun hooks(response: io.ktor.client.statement.HttpResponse) {
        val saved = response.call
        val savedRequest = saved.request
        val original = savedRequest.javaClass.getDeclaredField("\$\$delegate_0").apply { isAccessible = true }.get(savedRequest) as io.ktor.client.request.HttpRequest
        ktor.onSetResponse(original.call, original.call.response)
        // Ktor sets the response again on a call a plugin wraps: still one entry.
        ktor.onSetResponse(original.call, original.call.response)
        ktor.onSetResponse(saved, saved.response)
    }

    private fun all() = store.list(order = io.yamsergey.dta.protocol.network.Order.OLDEST_FIRST, limit = 100).entries.map { store.get(it.id)!! }
    private fun http() = all().filterIsInstance<HttpEntry>().single()

    @Test
    fun `a GET is recorded with its line, headers, status and the saved body`() = runBlocking {
        val r = client.get("http://127.0.0.1:8090/weather/places?q=osl") { header("X-Client", "test") }
        assertEquals("""{"places":[]}""", r.bodyAsText())
        hooks(r)
        val e = http()
        assertEquals("ktor-client", e.library)
        assertEquals("GET", e.method)
        assertEquals("http://127.0.0.1:8090/weather/places?q=osl", e.url)
        assertEquals(200, e.status)
        assertEquals(HttpState.COMPLETE, e.state)
        assertTrue(e.requestHeaders!!.any { it.name == "X-Client" && it.value == "test" })
        assertTrue(e.responseHeaders!!.any { it.name == HttpHeaders.ContentType })
        assertEquals(13L, e.responseBody!!.storedBytes)
    }

    @Test
    fun `a POST's text body is recorded`() = runBlocking {
        val r = client.post("http://h/api/cart/items") { contentType(ContentType.Application.Json); setBody("""{"productId":1}""") }
        hooks(r)
        val e = http()
        assertEquals("POST", e.method)
        assertEquals(15L, e.requestBody!!.storedBytes)
    }

    @Test
    fun `a 101 is a WebSocket connection, frames go to it both ways, close ends it`() {
        ktor.onSetResponseFor101("ws://127.0.0.1:8090/weather/alerts")
        ktor.onFrameSent(Frame.Text("""{"subscribe":"oslo"}"""))
        ktor.onFrameReceived(FrameType.TEXT, """{"type":"subscribed"}""".toByteArray())
        ktor.onFrameReceived(FrameType.PING, ByteArray(0))
        ktor.onFrameReceived(FrameType.CLOSE, byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray())
        val conn = all().filterIsInstance<WsConnectionEntry>().single()
        assertEquals(WsState.CLOSED, conn.state)
        assertEquals(1000, conn.closeCode)
        val messages = all().filterIsInstance<WsMessageEntry>()
        assertEquals(listOf(Direction.OUT, Direction.IN), messages.map { it.direction })
    }

    /** A 101 response object is not easy to build with MockEngine: the same path, through its data. */
    private fun KtorRecorder.onSetResponseFor101(url: String) {
        val r = runBlocking {
            HttpClient(MockEngine { respond("", HttpStatusCode.SwitchingProtocols) }).get(url.replace("ws://", "http://"))
        }
        val savedRequest = r.call.request
        val original = savedRequest.javaClass.getDeclaredField("\$\$delegate_0").apply { isAccessible = true }.get(savedRequest) as io.ktor.client.request.HttpRequest
        onSetResponse(original.call, original.call.response)
    }
}
