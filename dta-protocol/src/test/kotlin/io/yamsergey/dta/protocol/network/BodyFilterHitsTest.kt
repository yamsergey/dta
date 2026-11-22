package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-034: `body:main` listed entries whose bodies showed no "main" and nothing highlighted. Every
 * entry a body filter keeps now says where it matched: a hit per body, with the span in the text
 * `network.body` gives (decoded JSON for a schema-decoded body; bytes when binary), the part
 * (request, response, app-side, message) and an excerpt — and body: text is never fuzzy.
 */
class BodyFilterHitsTest {
    private fun info(part: BodyPart, binary: Boolean = false, type: String = "application/json") =
        BodyInfo(part, storedBytes = 10, contentType = type, binary = binary, completeness = Completeness.COMPLETE)

    private fun http(n: Int, request: Boolean = false, response: Boolean = true, app: Boolean = false, binary: Boolean = false) = HttpEntry(
        "r1:$n", "r1", n * 100L, "okhttp3", method = "POST", url = "https://api.example.test/v1/$n", state = HttpState.COMPLETE, status = 200,
        requestBody = if (request) info(BodyPart.REQUEST) else null,
        responseBody = if (response) info(BodyPart.RESPONSE, binary, if (binary) "application/x-protobuf" else "application/json") else null,
        appRequest = if (app) HttpRequestSnapshot("https://api.example.test/v1/$n", "POST", emptyList(), info(BodyPart.APP_REQUEST)) else null,
    )

    /** "main" inside a longer word: found, and the hit shows exactly where. */
    private val domain = http(1)
    /** Only typos of "main": not a match of body:main. */
    private val typos = http(2)
    /** In the request body only (the Payload tab), not the response. */
    private val request = http(3, request = true)
    /** In the app-side request (a script changed it), not on the wire. */
    private val appSide = http(4, request = true, app = true)
    /** A protobuf body a schema decodes: in its decoded JSON. */
    private val decoded = http(5, binary = true)
    /** A protobuf body no schema decodes: in its bytes. */
    private val raw = http(6, binary = true)
    /** In a WebSocket message. */
    private val message = WsMessageEntry("r1:7", "r1", 700, "okhttp3-ws", connectionId = "r1:0", direction = Direction.IN, opcode = MessageOpcode.TEXT, payload = info(BodyPart.MESSAGE))

    private val entries = listOf(domain, typos, request, appSide, decoded, raw, message)
    private val texts = mapOf(
        (domain.id to BodyPart.RESPONSE) to """{"user":"ann","domain":"example.test"}""",
        (typos.id to BodyPart.RESPONSE) to """{"weather":"rain","name":"cain","x":"majn","y":"ma-in"}""",
        (request.id to BodyPart.REQUEST) to """{"screen":"Main"}""",
        (request.id to BodyPart.RESPONSE) to """{"ok":true}""",
        (appSide.id to BodyPart.REQUEST) to """{"screen":"home"}""",
        (appSide.id to BodyPart.APP_REQUEST) to """{"screen":"MAIN"}""",
        (appSide.id to BodyPart.RESPONSE) to """{"ok":true}""",
        (message.id to BodyPart.MESSAGE) to """{"type":"tick","feed":"mainline"}""",
    )
    private val bytes = mapOf(
        (decoded.id to BodyPart.RESPONSE) to byteArrayOf(0x0A, 0x03, 'm'.code.toByte(), 0x00, 0x01),
        (raw.id to BodyPart.RESPONSE) to (byteArrayOf(0x0A, 0x04) + "main".toByteArray() + byteArrayOf(0x10, 0x01)),
    )
    private val decodedJson = mapOf((decoded.id to BodyPart.RESPONSE) to """{"screen":"remain","n":1}""")

    private fun view() = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, p -> texts[e.id to p]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } ?: bytes[e.id to p]?.let { NetworkFilterEval.BodyBytes(it, true) } },
        decoded = { e, p -> decodedJson[e.id to p] },
    )

    private fun shown(h: SearchHit): String = (texts[h.id to h.part] ?: decodedJson[h.id to h.part])!!.substring(h.start, h.end)

    @Test
    fun `every entry body-main lists says where - part, span and excerpt (D-034)`() {
        val page = view().list(NetworkQuery(q = "body:main", order = Order.OLDEST_FIRST))
        assertEquals(listOf(domain.id, request.id, appSide.id, decoded.id, raw.id, message.id), page.entries.map { it.id }, "typos of main are not body:main")
        val hits = page.hits!!
        assertEquals(page.entries.map { it.id }.toSet(), hits.keys, "no listed entry without its hit")

        val inWord = hits.getValue(domain.id).single()
        assertEquals(BodyPart.RESPONSE to SearchPlace.RESPONSE_BODY, inWord.part to inWord.place)
        assertEquals("main", shown(inWord), "inside \"domain\": the span is the found text, not the word")
        assertTrue(inWord.excerpt.render { "[$it]" }.contains("do[main]"), inWord.excerpt.toString())

        val req = hits.getValue(request.id).single()
        assertEquals(BodyPart.REQUEST to SearchPlace.REQUEST_BODY, req.part to req.place, "the request body: the Payload tab, not Response")
        assertEquals("Main", shown(req))

        val app = hits.getValue(appSide.id).single()
        assertEquals(BodyPart.APP_REQUEST, app.part, "what the app saw, not the wire")
        assertEquals("MAIN", shown(app))

        val dec = hits.getValue(decoded.id).single()
        assertEquals(BodyPart.RESPONSE, dec.part)
        assertEquals(false, dec.binary)
        assertEquals("main", shown(dec), "in the decoded JSON as network.body shows it")

        val bin = hits.getValue(raw.id).single()
        assertTrue(bin.binary, "no schema: found in the bytes, said so")
        assertEquals(2 to 6, bin.start to bin.end)
        assertTrue(bin.excerpt.render { "[$it]" }.contains("[main]"), bin.excerpt.toString())

        val msg = hits.getValue(message.id).single()
        assertEquals(BodyPart.MESSAGE to SearchPlace.PAYLOAD, msg.part to msg.place)
        assertEquals("main", shown(msg))
    }

    @Test
    fun `req and resp say their own part - the stream, search and live decisions carry the same hits`() {
        val v = view()
        assertEquals(listOf(request.id, appSide.id), v.list(NetworkQuery(q = "req:main", order = Order.OLDEST_FIRST)).entries.map { it.id })
        assertEquals(listOf(domain.id, decoded.id, raw.id), v.list(NetworkQuery(q = "resp:main", order = Order.OLDEST_FIRST)).entries.map { it.id })

        val q = NetworkQuery(q = "body:main", order = Order.OLDEST_FIRST)
        val listed = v.list(q).hits!!
        val found = ArrayList<FoundItem>()
        v.find(q) { found += it }
        assertEquals(listed, found.associate { it.entry.id to it.hits!! }, "the streamed rows carry the list's hits")
        assertEquals(listed, v.search(q).entries.associate { it.id to it.hits }, "network.search takes a body filter alone")
        for (e in entries) {
            val d = v.decide(q, e)
            assertEquals(listed[e.id], d.hits, "live decision of ${e.id}")
        }
    }

    @Test
    fun `body text is exact, never one typo away, typos on or off`() {
        val v = view()
        for (q in listOf("body:main", "typos:on body:main")) {
            assertNull(v.list(NetworkQuery(q = q)).entries.firstOrNull { it.id == typos.id }, q)
        }
        assertEquals(emptyList(), v.list(NetworkQuery(q = "body:majm")).entries)
    }

    @Test
    fun `hits are kept only for entries the whole filter keeps`() {
        val e = NetworkFilterEval(NetworkFilter(bodyContains = "main", responsePattern = "nothing-like-this"), "r1", { null },
            bodyBytes = { en, p -> texts[en.id to p]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } })
        assertNull(e.matches(domain))
        assertTrue(e.filterHits.isEmpty(), e.filterHits.toString())
    }

    @Test
    fun `redacted - the span is in the redacted text the answer shows`() {
        val secret = http(9, request = true)
        val body = """{"password":"main-secret","screen":"main"}"""
        val v = NetworkHistoryView(
            { listOf(secret) }, { secret.takeIf { s -> s.id == it } },
            { HistoryContext("r1", Coverage(searched = 0, stored = 1, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
            bodyBytes = { _, p -> if (p == BodyPart.REQUEST) NetworkFilterEval.BodyBytes(body.toByteArray(), true) else null },
        )
        val hit = v.list(NetworkQuery(q = "body:main", redact = true)).hits!!.getValue(secret.id).single()
        val redacted = Redactor.body(body, "application/json", "").text!!
        assertEquals("main", redacted.substring(hit.start, hit.end))
        assertTrue(redacted.indexOf("main") == hit.start, "the masked password is not where it is")
    }
}
