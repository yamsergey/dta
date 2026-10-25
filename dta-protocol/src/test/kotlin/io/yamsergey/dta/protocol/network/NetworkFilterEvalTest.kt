package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkFilterEvalTest {

    private fun body(part: BodyPart, binary: Boolean = false) =
        BodyInfo(part, storedBytes = 10, binary = binary, completeness = Completeness.COMPLETE)

    private val ok = HttpEntry("r2-1:1", "r2-1", 100, "okhttp3", method = "GET", url = "https://api.example.com/v1/auth/token", state = HttpState.COMPLETE, status = 200,
        responseBody = body(BodyPart.RESPONSE))
    private val notFound = HttpEntry("r2-1:2", "r2-1", 200, "okhttp3", method = "POST", url = "https://api.example.com/v1/users", state = HttpState.COMPLETE, status = 404,
        requestBody = body(BodyPart.REQUEST))
    private val failed = HttpEntry("r2-1:3", "r2-1", 300, "urlconnection", method = "GET", url = "https://cdn.example.net/a.png", state = HttpState.FAILED)
    private val pending = HttpEntry("r2-1:4", "r2-1", 400, "okhttp3", method = "GET", url = "https://api.example.com/slow", state = HttpState.PENDING)
    private val upload = HttpEntry("r2-1:5", "r2-1", 450, "okhttp3", method = "POST", url = "https://otel.example.com/v1/traces", state = HttpState.COMPLETE, status = 503,
        requestBody = body(BodyPart.REQUEST, binary = true))
    private val socket = WsConnectionEntry("r2-1:6", "r2-1", 500, "okhttp3-ws", url = "wss://push.example.com/socket", state = WsState.OPEN)
    private val message = WsMessageEntry("r2-1:7", "r2-1", 600, "okhttp3-ws", connectionId = socket.id, direction = Direction.IN, opcode = MessageOpcode.TEXT,
        payload = body(BodyPart.MESSAGE))
    private val oldRun = HttpEntry("r1-9:1", "r1-9", 50, "okhttp3", method = "GET", url = "https://api.example.com/v1/auth/token", state = HttpState.PROCESS_ENDED)

    private val all = listOf(oldRun, ok, notFound, failed, pending, upload, socket, message)

    private val texts = mapOf(
        (ok.id to BodyPart.RESPONSE) to """{"account_id":"A-1"}""",
        (notFound.id to BodyPart.REQUEST) to """{"name":"x"}""",
        (message.id to BodyPart.MESSAGE) to """{"event":"switch","account_id":"A-2"}""",
    )

    /** The OTLP upload: a span name inside protobuf bytes (field 5, length 8). */
    private val uploadBytes = byteArrayOf(0x0A, 0x02, 0x01, 0x02, 0x2A, 0x08) + "checkout".toByteArray() + byteArrayOf(0x00, 0xFF.toByte())

    private fun eval(filter: NetworkFilter, binary: Map<Pair<String, BodyPart>, NetworkFilterEval.BodyBytes> = mapOf((upload.id to BodyPart.REQUEST) to NetworkFilterEval.BodyBytes(uploadBytes, decoded = true))) =
        NetworkFilterEval(
            filter = filter,
            liveRunId = "r2-1",
            connectionUrl = { id -> (all.single { it.id == id } as WsConnectionEntry).url },
            bodyBytes = { entry, part -> texts[entry.id to part]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), decoded = true) } ?: binary[entry.id to part] },
        )

    private fun ids(filter: NetworkFilter): List<String> {
        val e = eval(filter)
        return all.filter { e.matches(it) != null }.map { it.id }
    }

    @Test
    fun `no filter matches everything`() {
        assertEquals(all.map { it.id }, ids(NetworkFilter()))
    }

    @Test
    fun `url is a case-insensitive substring, messages match by their connection`() {
        assertEquals(listOf(oldRun.id, ok.id), ids(NetworkFilter(url = "AUTH")))
        assertEquals(listOf(socket.id, message.id), ids(NetworkFilter(url = "push.example")))
        assertEquals(listOf(MatchLocation.URL), eval(NetworkFilter(url = "push")).matches(message))
    }

    @Test
    fun `method matches http only, case-insensitive`() {
        assertEquals(listOf(notFound.id, upload.id), ids(NetworkFilter(method = "post")))
    }

    @Test
    fun `status exact, class, failed and pending`() {
        assertEquals(listOf(notFound.id), ids(NetworkFilter(status = "404")))
        assertEquals(listOf(notFound.id), ids(NetworkFilter(status = "4xx")))
        assertEquals(listOf(upload.id), ids(NetworkFilter(status = "5xx")))
        assertEquals(listOf(failed.id), ids(NetworkFilter(status = "failed")))
        assertEquals(listOf(pending.id), ids(NetworkFilter(status = "pending")))
    }

    @Test
    fun `kinds, time range and runs`() {
        assertEquals(listOf(socket.id, message.id), ids(NetworkFilter(kinds = listOf(EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE))))
        assertEquals(listOf(socket.id), ids(NetworkFilter(url = "push", kinds = listOf(EntryKind.WS_CONNECTION))))
        assertEquals(listOf(failed.id, pending.id), ids(NetworkFilter(sinceMs = 300, untilMs = 400)))
        assertEquals(all.filter { it.runId == "r2-1" }.map { it.id }, ids(NetworkFilter(runs = RunScope.CURRENT)))
    }

    @Test
    fun `body contains searches decoded text bodies and payloads, with locations`() {
        val e = eval(NetworkFilter(bodyContains = "account_id"))
        assertEquals(listOf(ok.id, message.id), all.filter { e.matches(it) != null }.map { it.id })
        assertEquals(listOf(MatchLocation.RESPONSE_BODY), e.matches(ok))
        assertEquals(listOf(MatchLocation.PAYLOAD), e.matches(message))
    }

    @Test
    fun `body contains is case-sensitive`() {
        val e = eval(NetworkFilter(bodyContains = "ACCOUNT_ID"))
        assertEquals(emptyList(), all.filter { e.matches(it) != null })
        assertEquals(0, e.binaryNotSearched, "binary bodies are searched now")
    }

    @Test
    fun `body contains finds strings inside binary bodies and says the body is binary`() {
        val e = eval(NetworkFilter(bodyContains = "checkout"))
        assertEquals(listOf(upload.id), all.filter { e.matches(it) != null }.map { it.id })
        assertEquals(listOf(MatchLocation.REQUEST_BODY_BINARY), e.matches(upload))
        assertNull(eval(NetworkFilter(bodyContains = "checkouts")).matches(upload), "a needle running past the end")
        assertNull(eval(NetworkFilter(bodyContains = "Checkout")).matches(upload), "case-sensitive like text")
    }

    @Test
    fun `binary search uses UTF-8 needles and covers responses and payloads`() {
        val utf8 = byteArrayOf(0x12, 0x07) + "zürich".toByteArray(Charsets.UTF_8)
        val binaryResponse = ok.copy(id = "r2-1:8", responseBody = body(BodyPart.RESPONSE, binary = true))
        val binaryMessage = message.copy(id = "r2-1:9", opcode = MessageOpcode.BINARY, payload = body(BodyPart.MESSAGE, binary = true))
        val e = eval(
            NetworkFilter(bodyContains = "zürich"),
            mapOf(
                (binaryResponse.id to BodyPart.RESPONSE) to NetworkFilterEval.BodyBytes(utf8, decoded = true),
                (binaryMessage.id to BodyPart.MESSAGE) to NetworkFilterEval.BodyBytes(utf8, decoded = true),
            ),
        )
        assertEquals(listOf(MatchLocation.RESPONSE_BODY_BINARY), e.matches(binaryResponse))
        assertEquals(listOf(MatchLocation.PAYLOAD_BINARY), e.matches(binaryMessage))
    }

    @Test
    fun `binary bodies whose encoding cannot be undone are not searched, and counted`() {
        val e = eval(NetworkFilter(bodyContains = "checkout"), mapOf((upload.id to BodyPart.REQUEST) to NetworkFilterEval.BodyBytes(uploadBytes, decoded = false)))
        assertNull(e.matches(upload))
        assertEquals(1, e.binaryNotSearched)
    }

    @Test
    fun `filters combine with AND`() {
        assertEquals(listOf(ok.id), ids(NetworkFilter(url = "auth", status = "200", runs = RunScope.CURRENT, bodyContains = "A-1")))
        assertNull(eval(NetworkFilter(url = "auth", method = "POST")).matches(ok))
    }

    @Test
    fun `intervened keeps only entries the script touched, or only untouched ones`() {
        val touched = ok.copy(id = "r2-1:9", intervention = InterventionRecord("s", delayMs = 5))
        val e = NetworkFilterEval(NetworkFilter(intervened = true), "r2-1", { null }, { _, _ -> null })
        assertEquals(listOf(MatchLocation.URL).size - 1, e.matches(touched)!!.size)
        assertNull(e.matches(ok))
        val untouched = NetworkFilterEval(NetworkFilter(intervened = false), "r2-1", { null }, { _, _ -> null })
        assertNull(untouched.matches(touched))
        assertEquals(emptyList(), untouched.matches(ok))
    }

    @Test
    fun `body contains also searches the app-side body of a scripted entry`() {
        val scripted = ok.copy(id = "r2-1:8", appResponse = HttpResponseSnapshot(200, emptyList(), body(BodyPart.APP_RESPONSE)))
        val e = NetworkFilterEval(NetworkFilter(bodyContains = "patched"), "r2-1", { null }) { entry, part ->
            if (entry.id == scripted.id && part == BodyPart.APP_RESPONSE) NetworkFilterEval.BodyBytes("patched!".toByteArray(), decoded = true) else null
        }
        assertEquals(listOf(MatchLocation.RESPONSE_BODY), e.matches(scripted))
    }

    @Test
    fun `binary search over 1000 bodies of 64 KB stays well under a second`() {
        val random = java.util.Random(7)
        val entries = (1..1000).map { i ->
            upload.copy(id = "r2-1:p$i", requestBody = BodyInfo(BodyPart.REQUEST, 65_536, binary = true, completeness = Completeness.COMPLETE))
        }
        val bytes = entries.associate { it.id to ByteArray(65_536).also(random::nextBytes) }
        val e = NetworkFilterEval(NetworkFilter(bodyContains = "span-name-not-there"), "r2-1", { null }) { entry, _ ->
            NetworkFilterEval.BodyBytes(bytes.getValue(entry.id), decoded = true)
        }
        val start = System.nanoTime()
        assertEquals(0, entries.count { e.matches(it) != null })
        val ms = (System.nanoTime() - start) / 1_000_000
        println("SC-004 deviceless: 1000 x 64 KB binary bodies searched in $ms ms")
        kotlin.test.assertTrue(ms < 1000, "$ms ms")
    }
}
