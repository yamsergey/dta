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
        assertEquals("binary", binary.preview, "the size is in its own column, once")
    }

    @Test
    fun `a WebSocket connection says Connect, host and path, 101 while open, then the close`() {
        val open = row(6)
        assertEquals(EntryKind.WS_CONNECTION, open.kind)
        assertEquals("Connect", open.verb, "the handshake's GET is in its headers, not the list")
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
        assertTrue(d.summary.none { it.label == "Run" }, "the run id is for agents; a person reads the time")
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
        assertEquals("checkout outage", wire.intervention!!.description, "the script's own words")
        assertTrue("checkout outage" in wire.statements.first().sentence, "and in the scripted mark's sentence, for the list and the CLI")
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

    @Test
    fun `a connection's messages link to themselves, a message links to its connection (025)`() {
        val d = EntryDetail.of(canned("entry-ws"))
        assertEquals(listOf("r2-1:7", "r2-1:8"), d.messages.map { it.id })
        assertEquals(null, d.connection)

        val message = d.let { _ ->
            val m = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"entry":{"type":"ws-message","id":"r2-1:7","runId":"r2-1","timeMs":1700000006200,"library":"okhttp3-ws","connectionId":"r2-1:6","url":"wss://ws.example.com/feed","direction":"out","opcode":"text","payload":{"part":"message","storedBytes":11,"completeness":"complete"}}}""",
            ).jsonObject
            EntryDetail.of(m)
        }
        assertEquals(EntryLink("r2-1:6", "wss://ws.example.com/feed"), message.connection)
        assertEquals(Field("Message", "out text"), message.summary.first())

        val noUrl = EntryDetail.of(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"entry":{"type":"ws-message","id":"r2-1:7","runId":"r2-1","timeMs":1,"library":"okhttp3-ws","connectionId":"r2-1:6","direction":"in","opcode":"binary","payload":{"part":"message","storedBytes":1,"completeness":"complete"}}}""",
        ).jsonObject)
        assertEquals(EntryLink("r2-1:6", "connection r2-1:6"), noUrl.connection)
    }

    @Test
    fun `a multi-line message is one line in the list, not its first line only`() {
        assertEquals("orderUpdate {\"id\": 7, \"status\": \"filled\"}", oneLine("orderUpdate\n{\"id\": 7,\n  \"status\": \"filled\"}\n"))
        assertEquals("MESSAGE destination:/topic/quotes {\"p\":1}", oneLine("MESSAGE\ndestination:/topic/quotes\n\n{\"p\":1}"))
    }
}
