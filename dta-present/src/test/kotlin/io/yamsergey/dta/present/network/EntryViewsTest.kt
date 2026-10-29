package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.StatementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T026: rows of the live list and the entry detail, from canned results. */
class EntryViewsTest {
    private fun canned(name: String): JsonObject = Json.parseToJsonElement(javaClass.getResource("/canned/$name.json")!!.readText()).jsonObject
    private val list = canned("network-list")["entries"]!!.jsonArray.map { it.jsonObject }
    private fun row(i: Int) = EntryRows.of(list[i], ZoneOffset.UTC)

    @Test
    fun `an HTTP row reads time, kind, method, host and path, status, timing and size`() {
        val r = row(0)
        assertEquals("22:13:20.000", r.time)
        assertEquals(EntryKind.HTTP, r.kind)
        assertEquals("GET", r.verb)
        assertEquals("api.example.com", r.host)
        assertEquals("/v1/catalog", r.path)
        assertEquals("404", r.outcome.text)
        assertEquals("status-client", r.outcome.token)
        assertEquals("142 ms · 18.4 KB", r.timing)
        assertEquals("r1-1", r.run)
        assertEquals(emptyList(), r.marks.shown)
    }

    @Test
    fun `outcomes and marks state what happened`() {
        assertEquals("503" to "status-server", row(1).outcome.let { it.text to it.token })
        assertEquals(listOf(StatementKind.INCOMPLETE, StatementKind.SCRIPTED, StatementKind.BINARY), row(1).marks.shown.map { it.kind })
        assertEquals(StatementKind.PENDING, row(2).outcome.statement!!.kind)
        assertEquals(StatementKind.PROCESS_ENDED, row(3).outcome.statement!!.kind)
        assertEquals(StatementKind.FAILED, row(4).outcome.statement!!.kind)
        assertNull(row(0).outcome.statement)
    }

    @Test
    fun `a WebSocket message says Sent or Received and names its socket's URL, its payload muted after it`() {
        val m = row(5)
        assertEquals(EntryKind.WS_IN, m.kind)
        assertEquals("Received", m.verb)
        assertEquals("chat.example.com", m.host)
        assertEquals("/chat", m.path)
        assertEquals("{\"hello\":1}", m.preview)
        assertEquals("20 B", m.timing)
        val binary = row(8)
        assertEquals(EntryKind.WS_OUT, binary.kind)
        assertEquals("Sent", binary.verb)
        assertEquals("/chat?room=1", binary.path)
        assertEquals("binary 16 B", binary.preview)
    }

    @Test
    fun `a WebSocket connection reads like its handshake - GET, host and path, 101 while open, then the close`() {
        val open = row(6)
        assertEquals(EntryKind.WS_CONNECTION, open.kind)
        assertEquals("GET", open.verb)
        assertEquals("chat.example.com", open.host)
        assertEquals("/chat", open.path)
        assertNull(open.preview)
        assertEquals("101 · open", open.outcome.text)
        assertEquals("3 msgs", open.timing)
        val closed = row(7)
        assertEquals("closed 1000", closed.outcome.text)
        assertEquals("1.3 s · 2 msgs", closed.timing)
    }

    @Test
    fun `runs get short labels only when the list holds more than one`() {
        assertEquals(emptyMap<String, String>(), EntryRows.runLabels(listOf("r1-9", "r1-9")))
        assertEquals(mapOf("r1-9" to "1", "r2-4" to "2"), EntryRows.runLabels(listOf("r1-9", "r2-4", "r1-9")))
    }

    @Test
    fun `each kind has a word, so HTTP and WebSocket rows read apart`() {
        assertEquals(listOf("HTTP", "WS", "WS ↑", "WS ↓"), EntryKind.entries.map { it.label })
        assertEquals("WebSocket message received", EntryKind.WS_IN.description)
        assertEquals("WebSocket connection", EntryKind.WS_CONNECTION.description)
    }

    @Test
    fun `detail sections follow information md, with the first app frame marked`() {
        val d = EntryDetail.of(canned("entry-scripted"))
        assertEquals(listOf("Summary", "Script", "Request", "Response", "Initiator"), d.sections)
        assertEquals("POST https://api.example.com/v1/cart?x=1", d.summary.first().value)
        assertTrue(d.summary.any { it.label == "Status" && it.value == "503" })
        assertTrue(d.summary.any { it.label == "Library" && it.value == "okhttp3 · h2" })
        assertTrue(d.summary.any { it.label == "Run" && it.value == "r2-1" })
        assertEquals(listOf(StatementKind.SCRIPTED, StatementKind.REDACTED), d.statements.map { it.kind })
        val initiator = d.initiator!!
        assertEquals("OkHttp Dispatcher · enqueue", initiator.header)
        assertEquals(listOf(false, true, false), initiator.frames.map { it.first })
        assertEquals("com.example.shop.cart.CartRepository.checkout(CartRepository.kt:57)", initiator.frames[1].text)
        assertTrue(initiator.frames[1].app && !initiator.frames[0].app)
    }

    @Test
    fun `a frame without a line or a file says so the way a JVM stack does`() {
        assertEquals("a.B.c(B.kt:3)", FrameText.of("a.B", "c", "B.kt", 3))
        assertEquals("a.B.c(B.kt)", FrameText.of("a.B", "c", "B.kt", null))
        assertEquals("a.B.c(Unknown Source)", FrameText.of("a.B", "c", null, null))
    }

    @Test
    fun `a scripted entry switches between the wire and what the app saw`() {
        val wire = EntryDetail.of(canned("entry-scripted"))
        assertEquals("changed status, body · delayed 2000 ms", wire.intervention!!.summary)
        assertTrue(wire.intervention!!.hasAppSide)
        assertEquals(Side.WIRE, wire.side)
        assertEquals(listOf("Content-Type", "Retry-After"), wire.response!!.headers.map { it.first })
        assertEquals("response", wire.response!!.body!!.part)

        val app = EntryDetail.of(canned("entry-scripted"), Side.APP)
        assertEquals(listOf("Content-Type", "X-Scripted"), app.response!!.headers.map { it.first })
        assertEquals("app-response", app.response!!.body!!.part)
        assertEquals(wire.request, app.request, "the script changed only the response")
    }

    @Test
    fun `a WebSocket connection lists its messages, and a missing stack says why`() {
        val d = EntryDetail.of(canned("entry-ws"))
        assertEquals(listOf("Summary", "Handshake", "Initiator", "Messages"), d.sections)
        assertEquals(listOf(EntryKind.WS_OUT, EntryKind.WS_IN), d.messages.map { it.kind })
        assertEquals(StatementKind.BINARY, d.messages[1].marks.shown.single().kind)
        assertEquals("1 more message not shown", d.messagesMore)
        assertEquals("captured without a stack (process restarted)", d.initiator!!.missing)
    }
}
