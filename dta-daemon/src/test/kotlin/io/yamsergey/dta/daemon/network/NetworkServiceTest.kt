package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.TestWsServer
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.NetworkBodyResult
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import kotlin.test.Test
import io.yamsergey.dta.protocol.network.DecodingOutcome
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NetworkServiceTest {

    private val entry = HttpEntry(
        id = "r1-2:1", runId = "r1-2", timeMs = 5, library = "okhttp3",
        method = "GET", url = "http://h/a?token=SECRET-Q", state = HttpState.COMPLETE, status = 200,
        requestHeaders = listOf(io.yamsergey.dta.protocol.network.Header("Authorization", "Bearer SECRET-H")),
    )
    private val coverage = Coverage(searched = 1, stored = 1, persisted = true)
    private var lastParams: JsonObject? = null

    private val json = """{"ok":true}""".toByteArray()
    private val zipped = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(json) } }.toByteArray()
    private val proto = ByteArray(4_400_000) { (it % 251).toByte() }.copyOf(10)
    private val ts = com.google.protobuf.Timestamp.newBuilder().setSeconds(1_700_000_000).build().toByteArray()

    /** Requests of `entry` decode as a Timestamp — a stand-in for any selected schema. */
    private val decoding = io.yamsergey.dta.daemon.network.proto.BodyDecoding(
        listOf(
            io.yamsergey.dta.daemon.network.proto.DecodingRule { t ->
                if (t.url == entry.url && t.method == "GET" && t.part == io.yamsergey.dta.daemon.network.proto.MappingPart.REQUEST) {
                    io.yamsergey.dta.daemon.network.proto.Selection(com.google.protobuf.Timestamp.getDescriptor(), "protobuf", "s", "m1")
                } else null
            },
        ),
    )

    private val sockets = mapOf(
        "c1" to io.yamsergey.dta.protocol.network.WsConnectionEntry(
            id = "c1", runId = "r1-2", timeMs = 1, library = "okhttp3-ws", url = "ws://h/ws", state = io.yamsergey.dta.protocol.network.WsState.OPEN,
        ),
        "chat" to io.yamsergey.dta.protocol.network.WsMessageEntry(
            id = "chat", runId = "r1-2", timeMs = 2, library = "okhttp3-ws", connectionId = "c1",
            direction = io.yamsergey.dta.protocol.network.Direction.OUT, opcode = io.yamsergey.dta.protocol.network.MessageOpcode.BINARY,
            payload = BodyInfo(BodyPart.MESSAGE, 6, binary = true, completeness = Completeness.COMPLETE),
        ),
    )

    /** A protobuf Struct prints as a plain JSON object — a stand-in for any decoded body with secrets in it. */
    private val struct = com.google.protobuf.Struct.newBuilder()
        .putFields("password", com.google.protobuf.Value.newBuilder().setStringValue("SECRET-P").build())
        .putFields("name", com.google.protobuf.Value.newBuilder().setStringValue("visible").build())
        .build().toByteArray()

    private val structDecoding = io.yamsergey.dta.daemon.network.proto.BodyDecoding(
        listOf(
            io.yamsergey.dta.daemon.network.proto.DecodingRule { t ->
                if (t.part == io.yamsergey.dta.daemon.network.proto.MappingPart.REQUEST) {
                    io.yamsergey.dta.daemon.network.proto.Selection(com.google.protobuf.Struct.getDescriptor(), "protobuf", "s", "m3")
                } else null
            },
        ),
    )

    private val bodies = mapOf(
        "text" to (BodyInfo(BodyPart.RESPONSE, json.size.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE) to json),
        "gzip" to (BodyInfo(BodyPart.RESPONSE, zipped.size.toLong(), contentType = "application/json", encoding = "gzip", completeness = Completeness.COMPLETE) to zipped),
        "proto" to (BodyInfo(BodyPart.REQUEST, 4_400_000, contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE) to proto),
        "br" to (BodyInfo(BodyPart.RESPONSE, 3, contentType = "text/plain", encoding = "br", completeness = Completeness.COMPLETE) to byteArrayOf(1, 2, 3)),
        "secret" to (BodyInfo(BodyPart.REQUEST, 0, contentType = "application/json", completeness = Completeness.COMPLETE) to """{"password":"SECRET-P","name":"ok"}""".toByteArray()),
        "ts" to (BodyInfo(BodyPart.REQUEST, ts.size.toLong(), contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE) to ts),
        "tsbad" to (BodyInfo(BodyPart.REQUEST, 3, contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE) to byteArrayOf(0x0A, 0x7F, 0x41)),
        "struct" to (BodyInfo(BodyPart.REQUEST, struct.size.toLong(), contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE) to struct),
        "chat" to (BodyInfo(BodyPart.MESSAGE, ts.size.toLong(), binary = true, completeness = Completeness.COMPLETE) to ts),
        "cut" to (BodyInfo(BodyPart.RESPONSE, 2, contentType = "text/plain", completeness = Completeness.TRUNCATED, completenessReason = "bound") to "ab".toByteArray()),
        "unread" to (BodyInfo(BodyPart.RESPONSE, 2, declaredBytes = 2, contentType = "text/plain", completeness = Completeness.NOT_READ, completenessReason = "the app closed the body without reading it; 2 of 2 bytes were captured by the close") to "ok".toByteArray()),
    )

    @org.junit.jupiter.api.io.TempDir
    lateinit var tmp: java.io.File

    private fun registry() = MethodRegistry().apply {
        register("network_list") { ctx ->
            lastParams = ctx.params
            DtaJson.encodeToJsonElement(NetworkPage.serializer(), NetworkPage(listOf(entry), total = 1, coverage = coverage))
        }
        register("network_entry") { ctx ->
            lastParams = ctx.params
            val id = ctx.params!!["id"]!!.jsonPrimitive.content
            DtaJson.encodeToJsonElement(NetworkEntryDetail.serializer(), NetworkEntryDetail(sockets[id] ?: entry))
        }
        register("network_body") { ctx ->
            lastParams = ctx.params
            val (info, bytes) = bodies.getValue(ctx.params!!["id"]!!.jsonPrimitive.content)
            bytes.toList().chunked(4).forEach { ctx.attachBinary(it.toByteArray()) }
            DtaJson.encodeToJsonElement(NetworkBodyResult.serializer(), NetworkBodyResult(info))
        }
        register("network_clear") {
            DtaJson.encodeToJsonElement(ClearResult.serializer(), ClearResult(clearedEntries = 3, clearedAtMs = 9))
        }
    }

    private fun <T> withService(
        decoding: io.yamsergey.dta.daemon.network.proto.BodyDecoding = io.yamsergey.dta.daemon.network.proto.BodyDecoding.NONE,
        block: suspend (NetworkService, TestWsServer) -> T,
    ): T = runBlocking {
        TestWsServer(registry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            try {
                block(NetworkService(decoding) { connection }, server)
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `list sends the query and decodes the page`() = withService { service, _ ->
        val page = service.list(NetworkQuery(filter = NetworkFilter(url = "auth"), limit = 5))
        assertEquals(listOf(entry), page.entries)
        assertEquals(NetworkQuery(filter = NetworkFilter(url = "auth"), limit = 5), NetworkQuery.parse(lastParams))
    }

    @Test
    fun `entry sends the id and decodes the detail`() = withService { service, _ ->
        val detail = service.entry("r1-2:1")
        assertEquals(entry, detail.entry)
        assertEquals("r1-2:1", lastParams!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clear decodes the result`() = withService { service, _ ->
        assertEquals(ClearResult(3, 9), service.clear())
    }

    @Test
    fun `events are the decoded network entry events of that connection`() = withService { service, server ->
        val received = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { withTimeout(5.seconds) { service.events().first() } }
        delay(300)
        val session = server.sessions.single()
        session.sendEvent("other.event", buildJsonObject { put("x", 1) })
        session.sendEvent(
            NetworkEntryEvent.NAME,
            DtaJson.encodeToJsonElement(NetworkEntryEvent.serializer(), NetworkEntryEvent(entry, EntryChange.CREATED)),
        )
        assertEquals(NetworkEntryEvent(entry, EntryChange.CREATED), received.await())
    }

    @Test
    fun `text output decodes the content encoding and states it`() = withService { service, _ ->
        val result = service.body("gzip", BodyPart.RESPONSE)
        assertEquals("""{"ok":true}""", result.text)
        assertEquals("gzip", result.body.encoding)
        assertEquals("response", lastParams!!["part"]!!.jsonPrimitive.content)
    }

    @Test
    fun `text output refuses binary bodies with an actionable message`() = withService { service, _ ->
        val e = kotlin.test.assertFailsWith<BinaryBodyException> { service.body("proto", BodyPart.REQUEST) }
        // Every client reads this message: it names catalog operations and parameters, never Kotlin types.
        assertTrue("application/x-protobuf" in e.message!! && "the output parameter" in e.message!!, e.message)
        assertTrue("network.schema.add" in e.message!! && "network.mapping.add" in e.message!!, "says how to make it readable: ${e.message}")
        assertTrue("BodyOutput" !in e.message!! && "networkSchemaAdd" !in e.message!!, e.message)
    }

    @Test
    fun `every body result states its decoding`() = withService(decoding) { service, _ ->
        val text = service.body("text", BodyPart.RESPONSE)
        assertEquals(DecodingOutcome.NOT_APPLICABLE, text.decoding!!.outcome)
        assertEquals("""{"ok":true}""", text.text)
        val raw = service.body("proto", BodyPart.RESPONSE, BodyOutput.File(java.io.File(tmp, "p.bin").path))
        assertEquals(DecodingOutcome.NOT_APPLICABLE, raw.decoding!!.outcome)
    }

    @Test
    fun `a selected binary body comes back as JSON stating how it was decoded`() = withService(decoding) { service, _ ->
        val result = service.body("ts", BodyPart.REQUEST)
        assertEquals("\"2023-11-14T22:13:20Z\"", result.text)
        assertEquals(DecodingOutcome.DECODED, result.decoding!!.outcome)
        assertEquals("google.protobuf.Timestamp", result.decoding!!.type)
        assertEquals("m1", result.decoding!!.rule)
    }

    @Test
    fun `a selected body that does not parse is stated, not thrown, and raw bytes stay available`() = withService(decoding) { service, _ ->
        val result = service.body("tsbad", BodyPart.REQUEST)
        assertEquals(null, result.text)
        assertEquals(DecodingOutcome.NOT_DECODED, result.decoding!!.outcome)
        assertTrue("google.protobuf.Timestamp" in result.decoding!!.reason!!, result.decoding!!.reason)
        val raw = service.body("tsbad", BodyPart.REQUEST, BodyOutput.File(java.io.File(tmp, "bad.bin").path))
        assertContentEquals(byteArrayOf(0x0A, 0x7F, 0x41), java.io.File(raw.file!!).readBytes())
    }

    @Test
    fun `a WebSocket message is matched by its socket's URL and direction`() {
        val ws = io.yamsergey.dta.daemon.network.proto.BodyDecoding(
            listOf(
                io.yamsergey.dta.daemon.network.proto.DecodingRule { t ->
                    if (t.url == "ws://h/ws" && t.part == io.yamsergey.dta.daemon.network.proto.MappingPart.MESSAGE && t.direction == io.yamsergey.dta.protocol.network.Direction.OUT) {
                        io.yamsergey.dta.daemon.network.proto.Selection(com.google.protobuf.Timestamp.getDescriptor(), "protobuf", "s", "m7")
                    } else null
                },
            ),
        )
        withService(ws) { service, _ ->
            val result = service.body("chat", BodyPart.MESSAGE)
            assertEquals("m7", result.decoding!!.rule)
            assertEquals("\"2023-11-14T22:13:20Z\"", result.text)
        }
    }

    @Test
    fun `redaction redacts a decoded binary body instead of withholding it`() = withService(structDecoding) { service, _ ->
        val result = service.body("struct", BodyPart.REQUEST, redact = true)
        val text = result.text!!
        assertTrue("SECRET-P" !in text && "visible" in text, text)
        assertEquals(listOf("entry struct request body $.password"), result.redaction!!.masked)
        assertEquals(DecodingOutcome.DECODED, result.decoding!!.outcome)
        assertEquals(service.body("struct", BodyPart.REQUEST).decoding, result.decoding, "the same decoding as plain text output")
        val file = java.io.File(tmp, "struct.json")
        service.body("struct", BodyPart.REQUEST, BodyOutput.File(file.path, structured = true), redact = true)
        assertTrue("SECRET-P" !in file.readText() && "visible" in file.readText())
    }

    @Test
    fun `under redaction a binary body that does not decode is still withheld`() = withService(structDecoding) { service, _ ->
        val result = service.body("tsbad", BodyPart.REQUEST, redact = true)
        assertEquals(null, result.text)
        assertTrue(result.redaction!!.withheld.single().contains("binary"), "${result.redaction}")
        assertEquals(DecodingOutcome.NOT_DECODED, result.decoding!!.outcome)
        val text = service.body("secret", BodyPart.REQUEST, redact = true)
        assertTrue("SECRET-P" !in text.text!!, "text bodies unchanged")
    }

    @Test
    fun `structured file output writes the decoded JSON or fails with the reason`() = withService(decoding) { service, _ ->
        val out = java.io.File(tmp, "ts.json")
        val result = service.body("ts", BodyPart.REQUEST, BodyOutput.File(out.path, structured = true))
        assertEquals("\"2023-11-14T22:13:20Z\"", out.readText())
        assertEquals(DecodingOutcome.DECODED, result.decoding!!.outcome)
        val raw = service.body("ts", BodyPart.REQUEST, BodyOutput.File(java.io.File(tmp, "ts.bin").path))
        assertContentEquals(ts, java.io.File(raw.file!!).readBytes(), "unstructured file keeps the bytes (FR-005)")
        val e = kotlin.test.assertFailsWith<BodyNotDecodedException> {
            service.body("tsbad", BodyPart.REQUEST, BodyOutput.File(java.io.File(tmp, "bad.json").path, structured = true))
        }
        assertEquals(DecodingOutcome.NOT_DECODED, e.decoding.outcome)
        kotlin.test.assertFailsWith<BodyNotDecodedException> {
            service.body("proto", BodyPart.RESPONSE, BodyOutput.File(java.io.File(tmp, "na.json").path, structured = true))
        }
    }

    @Test
    fun `file output writes decoded or raw bytes with their sha256`() = withService { service, _ ->
        val decoded = java.io.File(tmp, "decoded.json")
        val r1 = service.body("gzip", BodyPart.RESPONSE, BodyOutput.File(decoded.path))
        kotlin.test.assertContentEquals(json, decoded.readBytes())
        assertEquals(json.size.toLong(), r1.bytes)
        assertEquals(sha256(json), r1.sha256)

        val raw = java.io.File(tmp, "raw.gz")
        service.body("gzip", BodyPart.RESPONSE, BodyOutput.File(raw.path, decoded = false))
        kotlin.test.assertContentEquals(zipped, raw.readBytes())

        val proto = java.io.File(tmp, "body.pb")
        val r3 = service.body("proto", BodyPart.REQUEST, BodyOutput.File(proto.path))
        assertEquals(sha256(this.proto), r3.sha256)
    }

    @Test
    fun `undecodable encodings and incomplete bodies are stated`() = withService { service, _ ->
        val br = service.body("br", BodyPart.RESPONSE, BodyOutput.File(java.io.File(tmp, "x.br").path))
        assertTrue("br" in br.note!!, br.note)
        val cut = service.body("cut", BodyPart.RESPONSE)
        assertEquals("ab", cut.text)
        assertTrue(io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_BODY_INCOMPLETE in cut.degraded!!.map { it.reason })
        val unread = service.body("unread", BodyPart.RESPONSE)
        assertEquals("ok", unread.text)
        assertEquals(null, unread.degraded, "a body the app did not read is not a gap (023)")
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `parts are sent by their wire names`() = withService { service, _ ->
        service.body("text", BodyPart.APP_RESPONSE)
        assertEquals("app-response", lastParams!!["part"]!!.jsonPrimitive.content)
    }

    // ---- redaction (US6) -------------------------------------------------------------

    @Test
    fun `redacted list and entry mask urls and headers and say so`() = withService { service, _ ->
        val page = service.list(NetworkQuery(redact = true))
        assertTrue("SECRET" !in page.entries.toString(), page.entries.toString())
        assertEquals(true, page.redaction?.applied)
        assertTrue(page.redaction!!.masked.any { "url.query token" in it })
        val detail = service.entry("r1-2:1", redact = true)
        assertTrue("SECRET" !in detail.toString(), detail.toString())
        assertTrue(detail.redaction!!.masked.any { "header Authorization" in it })
    }

    @Test
    fun `unredacted output is untouched and unmarked`() = withService { service, _ ->
        val page = service.list(NetworkQuery())
        assertEquals(listOf(entry), page.entries)
        kotlin.test.assertNull(page.redaction)
    }

    @Test
    fun `redacted json body masks values and keeps the rest`() = withService { service, _ ->
        val result = service.body("secret", BodyPart.REQUEST, redact = true)
        assertTrue("SECRET-P" !in result.text!! && "\"name\":\"ok\"" in result.text!!, result.text)
        assertEquals(listOf("entry secret request body $.password"), result.redaction!!.masked)
    }

    @Test
    fun `binary bodies are withheld under redaction unless explicitly allowed`() = withService { service, _ ->
        val file = java.io.File(tmp, "w.pb")
        val withheld = service.body("proto", BodyPart.REQUEST, BodyOutput.File(file.path), redact = true)
        kotlin.test.assertFalse(file.exists(), "nothing written")
        assertEquals(0, withheld.bytes)
        assertTrue(withheld.redaction!!.withheld.single().contains("binary"))
        assertTrue(io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_REDACTION_WITHHELD in withheld.degraded!!.map { it.reason })

        val allowed = service.body("proto", BodyPart.REQUEST, BodyOutput.File(file.path), redact = true, allowUnredacted = true)
        assertTrue(file.exists())
        assertEquals(false, allowed.redaction!!.applied, "marked as not redacted")
    }
}
