package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * With `redact`, what a query matches is the redacted text (040: masked values are not
 * searchable; 053: patterns match the redacted text), so no answer — rows, hits, excerpts —
 * can carry or point at a masked value. Without it, the same queries find them.
 */
class HistoryRedactionTest {
    private val secretJson = """{"token":"SEED-TOKEN-abc","email":"seed@example.com","name":"visible"}"""
    private val decodedJson = """{"password":"SEED-PROTO-1","order":"ord-42"}"""

    private fun json(part: BodyPart, text: String) = BodyInfo(part, text.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE)
    private val proto = BodyInfo(BodyPart.REQUEST, 8, contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE)

    private val entries = listOf<NetworkEntry>(
        HttpEntry("r1:1", "r1", 100, "okhttp3", method = "POST", url = "https://api.example.test/p?page=2", state = HttpState.COMPLETE, status = 200,
            requestBody = json(BodyPart.REQUEST, secretJson), responseBody = json(BodyPart.RESPONSE, secretJson)),
        HttpEntry("r1:2", "r1", 200, "okhttp3", method = "POST", url = "https://api.example.test/otel", state = HttpState.COMPLETE, status = 200, requestBody = proto),
    )
    private val bodies = mapOf("r1:1" to secretJson, "r1:2" to "\u0000\u0001binary")

    private fun view() = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(Charsets.ISO_8859_1), true) } },
        decoded = { e, _ -> if (e.id == "r1:2") decodedJson else null },
    )

    private fun ids(page: NetworkPage) = page.entries.map { it.id }

    /** NET-RED-013 + twin NET-RED-014. */
    @Test
    fun `a body pattern matches the redacted text`() {
        val v = view()
        for (q in listOf("body:/SEED-TOKEN-[a-z]+/", "req:/seed@example/", "resp:/SEED-TOKEN/", "body:/SEED-PROTO-\\d/")) {
            assertEquals(emptyList(), ids(v.list(NetworkQuery(q = q, redact = true))), "$q under redaction")
            assertEquals(emptyList(), v.search(NetworkQuery(q = q, redact = true)).entries.map { it.id }, "$q search under redaction")
            assertTrue(ids(v.list(NetworkQuery(q = q))).isNotEmpty(), "twin: $q matches without redaction")
        }
        val harmless = v.list(NetworkQuery(q = "body:/\"name\":\"visible\"/", redact = true))
        assertEquals(listOf("r1:1"), ids(harmless), "a harmless pattern still matches")
        val masked = v.list(NetworkQuery(q = "body:/\\[redacted len=\\d+ #[0-9a-f]{12}\\]/", redact = true))
        assertEquals(listOf("r1:2", "r1:1"), ids(masked), "the mask itself is in the redacted text")
        val wide = v.list(NetworkQuery(q = "body:/.+/", redact = true))
        val out = DtaJson.encodeToString(NetworkPage.serializer(), wide) + DtaJson.encodeToString(SearchPage.serializer(), v.search(NetworkQuery(q = "body:/.+/", redact = true)))
        assertFalse(listOf("SEED-TOKEN-abc", "seed@example.com", "SEED-PROTO-1").any { it in out }, "no excerpt carries a masked value: $out")
    }

    /** Words inside a binary body a schema decodes are searched in its redacted rendering, excerpts included. */
    @Test
    fun `search reads a decoded binary body as redacted`() {
        val v = view()
        assertEquals(emptyList(), v.search(NetworkQuery(q = "SEED-PROTO-1", redact = true)).entries.map { it.id })
        assertEquals(listOf("r1:2"), v.search(NetworkQuery(q = "SEED-PROTO-1")).entries.map { it.id }, "twin: found without redaction")
        val near = v.search(NetworkQuery(q = "ord-42", redact = true))
        assertEquals(listOf("r1:2"), near.entries.map { it.id }, "a harmless value is still found")
        val out = DtaJson.encodeToString(SearchPage.serializer(), near)
        assertFalse("SEED-PROTO-1" in out, "the excerpt around it shows the mask: $out")
    }

    /** 040: masked values are not searchable — the text filters of the query line read the redacted text too. */
    @Test
    fun `text filters match the redacted url, headers and bodies`() {
        val headed = HttpEntry("r1:3", "r1", 300, "okhttp3", method = "GET", url = "https://api.example.test/q?page=2&access_token=SEED-QUERY-123", state = HttpState.COMPLETE, status = 200,
            requestHeaders = listOf(Header("Authorization", "Bearer SEED-AUTH-456"), Header("Accept", "application/json")))
        val all = entries + headed
        val v = NetworkHistoryView(
            { all }, { id -> all.firstOrNull { it.id == id } },
            { HistoryContext("r1", Coverage(searched = 0, stored = all.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
            bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(Charsets.ISO_8859_1), true) } },
            decoded = { e, _ -> if (e.id == "r1:2") decodedJson else null },
        )
        for (q in listOf("body:SEED-TOKEN-abc", "req:seed@example.com", "resp:SEED-TOKEN", "body:SEED-PROTO-1", "url:SEED-QUERY-123", "header:authorization=SEED-AUTH")) {
            assertEquals(emptyList(), ids(v.list(NetworkQuery(q = q, redact = true))), "$q under redaction")
            assertTrue(ids(v.list(NetworkQuery(q = q))).isNotEmpty(), "twin: $q matches without redaction")
        }
        for (q in listOf("body:visible", "url:page=2", "header:accept=json", "header:authorization", "body:ord-42")) {
            assertTrue(ids(v.list(NetworkQuery(q = q, redact = true))).isNotEmpty(), "a harmless $q still matches under redaction")
        }
    }
}
