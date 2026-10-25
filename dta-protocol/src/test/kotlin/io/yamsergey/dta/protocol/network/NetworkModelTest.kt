package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkModelTest {

    private val initiator = Initiator(
        thread = "main",
        site = InitiatorSite.ENQUEUE,
        frames = listOf(
            StackFrame("okhttp3.internal.connection.RealCall", "enqueue", "RealCall.kt", 160, app = false),
            StackFrame("com.example.Repo", "load", "Repo.kt", 12, app = true),
        ),
        firstAppFrame = 1,
    )

    private val http = HttpEntry(
        id = "r1-42:1", runId = "r1-42", timeMs = 1000, library = "okhttp3", initiator = initiator,
        method = "POST", url = "http://h/x?a=1", state = HttpState.COMPLETE, status = 200, durationMs = 12,
        requestHeaders = listOf(Header("Content-Type", "application/x-protobuf")),
        responseHeaders = listOf(Header("Content-Length", "2")),
        requestBody = BodyInfo(BodyPart.REQUEST, storedBytes = 4096, contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE),
        responseBody = BodyInfo(BodyPart.RESPONSE, storedBytes = 2, declaredBytes = 2, contentType = "application/json", completeness = Completeness.COMPLETE, preview = "{}"),
        protocol = "http/1.1",
    )

    private val wsConnection = WsConnectionEntry(
        id = "r1-42:2", runId = "r1-42", timeMs = 1100, library = "okhttp3-ws", initiator = initiator.copy(site = InitiatorSite.CONNECT),
        url = "ws://h/ws", state = WsState.CLOSED, openedAtMs = 1105, closedAtMs = 1300, closeCode = 1000, closeReason = "bye",
        handshakeHeaders = listOf(Header("Upgrade", "websocket")), messageCount = 2,
    )

    private val wsMessage = WsMessageEntry(
        id = "r1-42:3", runId = "r1-42", timeMs = 1200, library = "okhttp3-ws", initiator = null,
        connectionId = "r1-42:2", direction = Direction.IN, opcode = MessageOpcode.BINARY,
        payload = BodyInfo(BodyPart.MESSAGE, storedBytes = 16, binary = true, completeness = Completeness.COMPLETE),
    )

    private fun roundTrip(entry: NetworkEntry): NetworkEntry =
        DtaJson.decodeFromString(NetworkEntry.serializer(), DtaJson.encodeToString(NetworkEntry.serializer(), entry))

    @Test
    fun `each entry kind round-trips with its type discriminator`() {
        for ((entry, type) in listOf(http to "http", wsConnection to "ws-connection", wsMessage to "ws-message")) {
            val json = DtaJson.encodeToJsonElement(NetworkEntry.serializer(), entry).jsonObject
            assertEquals(type, json["type"]!!.jsonPrimitive.content)
            assertEquals(entry, roundTrip(entry))
        }
    }

    @Test
    fun `enums use their wire names`() {
        val json = DtaJson.encodeToString(NetworkEntry.serializer(), http.copy(state = HttpState.PROCESS_ENDED))
        assertTrue("\"process-ended\"" in json, json)
        assertTrue("\"enqueue\"" in json, json)
        val message = DtaJson.encodeToString(NetworkEntry.serializer(), wsMessage)
        assertTrue("\"direction\":\"in\"" in message && "\"opcode\":\"binary\"" in message, message)
    }

    @Test
    fun `absent optionals are omitted, not null`() {
        val json = DtaJson.encodeToString(NetworkEntry.serializer(), wsMessage)
        assertFalse("null" in json, json)
        assertFalse("initiator" in json, json)
    }

    @Test
    fun `every completeness value round-trips and non-complete requires a reason`() {
        for (c in Completeness.entries) {
            val body = BodyInfo(BodyPart.RESPONSE, storedBytes = 1, completeness = c, completenessReason = if (c == Completeness.COMPLETE) null else "why")
            assertEquals(body, DtaJson.decodeFromString(BodyInfo.serializer(), DtaJson.encodeToString(BodyInfo.serializer(), body)))
        }
        assertEquals(
            listOf("complete", "truncated", "partially-observed", "omitted", "dropped"),
            Completeness.entries.map { DtaJson.encodeToJsonElement(Completeness.serializer(), it).jsonPrimitive.content },
        )
        assertFailsWith<IllegalArgumentException> { BodyInfo(BodyPart.RESPONSE, storedBytes = 1, completeness = Completeness.TRUNCATED) }
    }

    @Test
    fun `initiator states its absence instead of being empty`() {
        val missing = Initiator(thread = "OkHttp Dispatcher", site = InitiatorSite.CALL, missing = "call was not seen at enqueue")
        assertEquals(missing, DtaJson.decodeFromString(Initiator.serializer(), DtaJson.encodeToString(Initiator.serializer(), missing)))
        assertFailsWith<IllegalArgumentException> { Initiator(thread = "t", site = InitiatorSite.CALL) }
    }

    @Test
    fun `summary drops headers but keeps body infos`() {
        val summary = http.summary() as HttpEntry
        assertNull(summary.requestHeaders)
        assertNull(summary.responseHeaders)
        assertEquals(http.requestBody, summary.requestBody)
        assertNull((wsConnection.summary() as WsConnectionEntry).handshakeHeaders)
        assertEquals(wsMessage, wsMessage.summary())
    }

    @Test
    fun `page, coverage, runs and redaction round-trip`() {
        val page = NetworkPage(
            entries = listOf(http.summary(), wsConnection.summary(), wsMessage),
            matches = mapOf("r1-42:1" to listOf(MatchLocation.REQUEST_BODY, MatchLocation.URL)),
            total = 7,
            next = "r1-42:3",
            coverage = Coverage(
                searched = 7, stored = 7, earliestMs = 1000, evicted = 3, dropped = 1, persisted = true,
                runs = listOf(
                    ProcessRun("r0-41", pid = 41, startedAtMs = 10, endedAtMs = 900, ended = RunEnd.PROCESS_ENDED),
                    ProcessRun("r1-42", pid = 42, startedAtMs = 950),
                ),
                notSearched = listOf("binary bodies (1)"),
                clearedAtMs = 5,
            ),
            redaction = RedactionInfo(masked = listOf("entry r1-42:1 request.header Authorization"), withheld = listOf("entry r1-42:1 request body (binary)")),
        )
        val json = DtaJson.encodeToString(NetworkPage.serializer(), page)
        assertTrue("\"request-body\"" in json && "\"process-ended\"" in json, json)
        assertEquals(page, DtaJson.decodeFromString(NetworkPage.serializer(), json))
    }

    @Test
    fun `entry detail carries a connection's messages`() {
        val detail = NetworkEntryDetail(entry = wsConnection, messages = listOf(wsMessage), messagesTruncated = 3)
        assertEquals(detail, DtaJson.decodeFromString(NetworkEntryDetail.serializer(), DtaJson.encodeToString(NetworkEntryDetail.serializer(), detail)))
    }

    @Test
    fun `network capability carries libraries and bounds, old capabilities stay compatible`() {
        val status = CapabilityStatus(
            "network", CapabilityStatus.Status.DEGRADED, "okhttp3 hook failed",
            libraries = listOf(
                LibraryStatus("okhttp3", CapabilityStatus.Status.UNAVAILABLE, "RetransformClasses: JVMTI_ERROR_INTERNAL"),
                LibraryStatus("urlconnection", CapabilityStatus.Status.AVAILABLE),
            ),
            bounds = NetworkBounds(historyBytes = 104_857_600, bodyBytes = 33_554_432),
        )
        assertEquals(status, DtaJson.decodeFromString(CapabilityStatus.serializer(), DtaJson.encodeToString(CapabilityStatus.serializer(), status)))

        val old = DtaJson.decodeFromString(CapabilityStatus.serializer(), """{"name":"channel","status":"available"}""")
        assertNull(old.libraries)
        assertNull(old.bounds)
        assertFalse("libraries" in DtaJson.encodeToString(CapabilityStatus.serializer(), old))
    }

    @Test
    fun `entry event round-trips`() {
        val event = NetworkEntryEvent(entry = http.summary(), change = EntryChange.UPDATED)
        val json = DtaJson.encodeToString(NetworkEntryEvent.serializer(), event)
        assertTrue("\"change\":\"updated\"" in json, json)
        assertEquals(event, DtaJson.decodeFromString(NetworkEntryEvent.serializer(), json))
    }

    @Test
    fun `degradation vocabulary has the eight network codes`() {
        assertEquals(
            listOf(
                "network-hooks-unavailable", "network-library-unavailable", "network-history-evicted",
                "network-history-not-persisted", "network-events-dropped", "network-initiator-missing",
                "network-body-incomplete", "network-redaction-withheld",
            ),
            listOf(
                DegradationCodes.NETWORK_HOOKS_UNAVAILABLE, DegradationCodes.NETWORK_LIBRARY_UNAVAILABLE,
                DegradationCodes.NETWORK_HISTORY_EVICTED, DegradationCodes.NETWORK_HISTORY_NOT_PERSISTED,
                DegradationCodes.NETWORK_EVENTS_DROPPED, DegradationCodes.NETWORK_INITIATOR_MISSING,
                DegradationCodes.NETWORK_BODY_INCOMPLETE, DegradationCodes.NETWORK_REDACTION_WITHHELD,
            ),
        )
    }
}
