package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkSearchTest {
    private fun body(part: BodyPart, binary: Boolean = false, type: String? = "application/json", completeness: Completeness = Completeness.COMPLETE) =
        BodyInfo(part, storedBytes = 10, binary = binary, contentType = type, completeness = completeness,
            completenessReason = if (completeness == Completeness.COMPLETE) null else "per-body bound")

    private val login = HttpEntry(
        "r1:1", "r1", 100, "okhttp3", method = "POST", url = "https://api.example.test/v1/login?token=s3cret", state = HttpState.COMPLETE, status = 401,
        requestHeaders = listOf(Header("Authorization", "Bearer abc"), Header("X-Order-Id", "7")),
        requestBody = body(BodyPart.REQUEST), responseBody = body(BodyPart.RESPONSE),
    )
    private val socket = WsConnectionEntry("r1:2", "r1", 200, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
    private val tick = WsMessageEntry("r1:3", "r1", 300, "okhttp3-ws", connectionId = socket.id, direction = Direction.IN, opcode = MessageOpcode.TEXT,
        payload = body(BodyPart.MESSAGE), envelope = Envelope("json", kind = "tick", topic = "ACME"))
    private val upload = HttpEntry("r1:4", "r1", 400, "okhttp3", method = "POST", url = "https://otel.example.test/v1/traces", state = HttpState.COMPLETE, status = 200,
        requestBody = body(BodyPart.REQUEST, binary = true, type = "application/x-protobuf"))
    private val gz = HttpEntry("r1:5", "r1", 500, "okhttp3", method = "GET", url = "https://api.example.test/big", state = HttpState.COMPLETE, status = 200,
        responseBody = body(BodyPart.RESPONSE, completeness = Completeness.TRUNCATED))

    private val texts = mapOf(
        (login.id to BodyPart.REQUEST) to """{"user":"ann","password":"hunter2"}""",
        (login.id to BodyPart.RESPONSE) to """{"error":"Token expired","order_id":7}""",
        (tick.id to BodyPart.MESSAGE) to """{"type":"tick","symbol":"ACME","price":20.7}""",
        (gz.id to BodyPart.RESPONSE) to """{"note":"expired"}""",
    )
    private val bytes = mapOf((upload.id to BodyPart.REQUEST) to (byteArrayOf(0x0A, 0x02) + "checkout".toByteArray() + byteArrayOf(0x00)))

    private fun search(vararg terms: String, redact: Boolean = false, undecodable: Set<String> = emptySet()) = NetworkSearch(
        terms.map { if (' ' in it) Term(it, true) else Term(it) },
        connectionUrl = { socket.url },
        bodyBytes = { e, p ->
            if (e.id in undecodable) NetworkFilterEval.BodyBytes(ByteArray(3), decoded = false)
            else texts[e.id to p]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } ?: bytes[e.id to p]?.let { NetworkFilterEval.BodyBytes(it, true) }
        },
        redact = redact,
    )

    @Test
    fun `a hit names its place and span in that place's text`() {
        val hits = search("token expired").hits(login)!!
        val body = hits.single { it.place == SearchPlace.RESPONSE_BODY }
        assertEquals(BodyPart.RESPONSE, body.part)
        assertEquals("Token expired", texts[login.id to BodyPart.RESPONSE]!!.substring(body.start, body.end))
        assertTrue(body.excerpt.render { "[$it]" }.contains("[Token expired]"), body.excerpt.toString())
    }

    @Test
    fun `every term must be in the entry, anywhere`() {
        val hits = search("orderid", "login").hits(login)!!
        assertEquals(setOf(SearchPlace.REQUEST_HEADER, SearchPlace.RESPONSE_BODY, SearchPlace.URL), hits.map { it.place }.toSet())
        assertTrue(hits.any { it.header == "X-Order-Id" && it.inName })
        assertNull(search("orderid", "nowhere").hits(login))
    }

    @Test
    fun `messages by payload and envelope, connections by URL and handshake`() {
        assertEquals(setOf(SearchPlace.PAYLOAD, SearchPlace.ENVELOPE), search("acme").hits(tick)!!.map { it.place }.toSet())
        assertEquals(SearchPlace.URL, search("live").hits(socket)!!.single().place)
    }

    @Test
    fun `binary bodies are searched as bytes or as their decoded JSON`() {
        val raw = search("checkout").hits(upload)!!.single()
        assertTrue(raw.binary)
        assertTrue(raw.excerpt.text.contains("checkout"), raw.excerpt.text)
        val decoded = NetworkSearch(listOf(Term("checkout")), { null }, decoded = { _, _ -> """{"name":"checkout"}""" }, bodyBytes = { _, _ -> null }).hits(upload)!!.single()
        assertEquals(false, decoded.binary)
        assertEquals(9 to 17, decoded.start to decoded.end)
    }

    @Test
    fun `what was not searched is counted`() {
        val s = search("expired", undecodable = setOf(login.id))
        assertNull(s.hits(login))
        assertEquals(2, s.notSearched)
        val t = search("expired")
        assertTrue(t.hits(gz) != null)
        assertEquals(1, t.truncated)
    }

    @Test
    fun `redacted values are not found`() {
        assertTrue(search("s3cret").hits(login) != null)
        assertNull(search("s3cret", redact = true).hits(login))
        assertNull(search("hunter2", redact = true).hits(login))
        assertNull(search("abc", redact = true).hits(login))
        assertTrue(search("ann", redact = true).hits(login) != null)
    }

    @Test
    fun `no terms, no hits`() {
        assertNull(search().hits(login))
    }

    @Test
    fun `a body the host has no copy of is searched by its preview and counted`() {
        val uncopied = tick.copy(id = "r1:9", payload = body(BodyPart.MESSAGE).copy(preview = """{"type":"order","symbol":"WAYNE"}"""))
        val s = search("wayne")
        val hit = s.hits(uncopied)!!.single()
        assertEquals(SearchPlace.PAYLOAD, hit.place)
        assertEquals(1, s.byPreview)
        // Nothing known at all: still counted, not skipped silently.
        val s2 = search("wayne")
        assertNull(s2.hits(tick.copy(id = "r1:10")))
        assertEquals(1, s2.notSearched)
    }

}
