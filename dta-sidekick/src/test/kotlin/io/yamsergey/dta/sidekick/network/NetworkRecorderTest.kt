package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkRecorderTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7, clock = { now }) }
    private val events = mutableListOf<NetworkEntryEvent>()
    private val recorder by lazy {
        NetworkRecorder(store, clock = { now }) { name, data: JsonElement? ->
            assertEquals(NetworkEntryEvent.NAME, name)
            events += DtaJson.decodeFromJsonElement(NetworkEntryEvent.serializer(), data!!)
        }
    }
    /** Feature 068: a history kept in memory only is the one still sent entry by entry (a journal goes as its feed). */
    private val memory by lazy { NetworkStore(null, pid = 7, clock = { now }) }
    private val announcing by lazy {
        NetworkRecorder(memory, clock = { now }) { name, data: JsonElement? ->
            assertEquals(NetworkEntryEvent.NAME, name)
            events += DtaJson.decodeFromJsonElement(NetworkEntryEvent.serializer(), data!!)
        }
    }
    private val initiator = Initiator(thread = "main", site = InitiatorSite.CALL, missing = "not captured in this test")

    @AfterEach
    fun close() = store.close()

    private fun entry(id: String) = store.get(id)!!

    @Test
    fun `http start is a pending entry announced as created`() {
        val id = announcing.httpStarted("okhttp3", "GET", "http://h/a", listOf(Header("Accept", "*/*")), initiator)
        val e = memory.get(id)!! as HttpEntry
        assertEquals(HttpState.PENDING, e.state)
        assertEquals(listOf(Header("Accept", "*/*")), e.requestHeaders)
        assertEquals(initiator, e.initiator)
        assertEquals(EntryChange.CREATED, events.single().change)
        assertNull((events.single().entry as HttpEntry).requestHeaders, "events carry the summary")
    }

    @Test
    fun `response then body end completes the exchange with duration and body info`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        now += 5
        recorder.httpResponse(id, 200, listOf(Header("Content-Type", "text/plain")), "http/1.1")
        assertEquals(HttpState.PENDING, (entry(id) as HttpEntry).state, "still pending until the body ends")
        val body = recorder.httpResponseBody(id, contentType = "text/plain", encoding = null, declaredBytes = 3)
        now += 7
        TeeInputStream(ByteArrayInputStream("abc".toByteArray()), body).use { it.readBytes() }
        val e = entry(id) as HttpEntry
        assertEquals(HttpState.COMPLETE, e.state)
        assertEquals(200, e.status)
        assertEquals(12, e.durationMs)
        assertEquals("http/1.1", e.protocol)
        assertEquals(3, e.responseBody!!.storedBytes)
        assertEquals(3, e.responseBody!!.declaredBytes)
        assertEquals(Completeness.COMPLETE, e.responseBody!!.completeness)
        assertContentEquals("abc".toByteArray(), store.openBody(id, BodyPart.RESPONSE)!!.readBytes())
    }

    @Test
    fun `a history kept in memory announces each change of an entry`() {
        val id = announcing.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        announcing.httpResponse(id, 200, emptyList(), "http/1.1")
        val body = announcing.httpResponseBody(id, contentType = "text/plain", encoding = null, declaredBytes = 3)
        TeeInputStream(ByteArrayInputStream("abc".toByteArray()), body).use { it.readBytes() }
        assertEquals(listOf(EntryChange.CREATED, EntryChange.UPDATED, EntryChange.UPDATED), events.map { it.change })
    }

    @Test
    fun `a history in a journal is not announced entry by entry`() {
        recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        assertEquals(emptyList(), events, "it reaches this computer as the journal's feed (068)")
    }

    @Test
    fun `a response without a body completes directly`() {
        val id = recorder.httpStarted("okhttp3", "HEAD", "http://h/a", emptyList(), initiator)
        recorder.httpResponse(id, 204, emptyList(), null)
        recorder.httpComplete(id)
        assertEquals(HttpState.COMPLETE, (entry(id) as HttpEntry).state)
    }

    @Test
    fun `request body is recorded on the request part`() {
        val id = recorder.httpStarted("okhttp3", "POST", "http://h/a", emptyList(), initiator)
        val body = recorder.httpRequestBody(id, contentType = "application/x-protobuf", declaredBytes = 4)
        body.accept(byteArrayOf(1, 2, 3, 4), 0, 4)
        body.finish(endOfStream = true)
        val e = entry(id) as HttpEntry
        assertEquals(4, e.requestBody!!.storedBytes)
        assertEquals(BodyPart.REQUEST, e.requestBody!!.part)
        assertEquals("application/x-protobuf", e.requestBody!!.contentType)
    }

    @Test
    fun `a compressed request body states its encoding`() {
        val id = recorder.httpStarted("okhttp3", "POST", "http://h/v1/traces", emptyList(), initiator)
        val body = recorder.httpRequestBody(id, contentType = "application/x-protobuf", declaredBytes = -1, encoding = "gzip")
        body.accept(byteArrayOf(0x1f, 0x8b.toByte()), 0, 2)
        body.finish(endOfStream = true)
        assertEquals("gzip", (entry(id) as HttpEntry).requestBody!!.encoding)
    }

    @Test
    fun `failure is stated with exception class and message`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        now += 3
        recorder.httpFailed(id, IOException("Connection refused"))
        val e = entry(id) as HttpEntry
        assertEquals(HttpState.FAILED, e.state)
        assertEquals("java.io.IOException: Connection refused", e.failure)
        assertEquals(3, e.durationMs)
    }

    @Test
    fun `failure after the response keeps the status and marks the exchange failed`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        recorder.httpResponse(id, 200, emptyList(), null)
        recorder.httpFailed(id, IOException("stream reset"))
        val e = entry(id) as HttpEntry
        assertEquals(HttpState.FAILED, e.state)
        assertEquals(200, e.status)
    }

    @Test
    fun `websocket lifecycle and messages`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", listOf(Header("Upgrade", "websocket")), initiator)
        assertEquals(WsState.CONNECTING, (entry(id) as WsConnectionEntry).state)
        now += 2
        recorder.wsOpened(id)
        val out = recorder.wsMessage(id, Direction.OUT, MessageOpcode.TEXT, "hi".toByteArray(), initiator)!!
        val inbound = recorder.wsMessage(id, Direction.IN, MessageOpcode.BINARY, byteArrayOf(9, 9, 9), null)!!
        now += 10
        recorder.wsClosed(id, 1000, "bye")

        val c = entry(id) as WsConnectionEntry
        assertEquals(WsState.CLOSED, c.state)
        assertEquals(1002, c.openedAtMs)
        assertEquals(1012, c.closedAtMs)
        assertEquals(1000, c.closeCode)
        assertEquals("bye", c.closeReason)
        assertEquals(2, c.messageCount)

        val m1 = entry(out) as WsMessageEntry
        assertEquals(id, m1.connectionId)
        assertEquals("ws://h/ws", m1.url, "a message names its socket without the connection entry")
        assertEquals(Direction.OUT, m1.direction)
        assertEquals(2, m1.payload.storedBytes)
        assertEquals(initiator, m1.initiator)
        val m2 = entry(inbound) as WsMessageEntry
        assertNull(m2.initiator)
        assertContentEquals(byteArrayOf(9, 9, 9), store.openBody(inbound, BodyPart.MESSAGE)!!.readBytes())
    }

    /** NET-WS-002: nv-websocket tells the open on the app's thread and the head later, from its own threads. */
    @Test
    fun `a second open keeps the first open time and takes the head`() {
        val id = recorder.wsStarted("nv-websocket", "ws://h/ws", emptyList(), initiator)
        recorder.wsOpened(id)
        val openedAt = now
        now += 5
        recorder.wsMessage(id, Direction.OUT, MessageOpcode.TEXT, "hi".toByteArray(), initiator)
        now += 5
        recorder.wsOpened(id, listOf(Header("Upgrade", "websocket")))
        val c = entry(id) as WsConnectionEntry
        assertEquals(openedAt, c.openedAtMs)
        assertEquals(listOf(Header("Upgrade", "websocket")), c.handshakeHeaders)
    }

    /** The twin: a late open on a connection already closed does not reopen it. */
    @Test
    fun `an open after the close does not reopen the connection`() {
        val id = recorder.wsStarted("nv-websocket", "ws://h/ws", emptyList(), initiator)
        recorder.wsOpened(id)
        recorder.wsClosed(id, 1000, "done")
        recorder.wsOpened(id, listOf(Header("Upgrade", "websocket")))
        assertEquals(WsState.CLOSED, (entry(id) as WsConnectionEntry).state)
    }

    /** D-002 (NET-WS-001/002): the close frame that started the closing handshake is the connection's close. */
    @Test
    fun `the app's close frame is the connection's close, not the server's empty echo`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        recorder.wsOpened(id)
        recorder.wsClosing(id, 1000, "done")
        assertEquals(WsState.OPEN, (entry(id) as WsConnectionEntry).state, "open until the closing handshake ends")
        now += 3
        recorder.wsClosed(id, 1000, "")
        val c = entry(id) as WsConnectionEntry
        assertEquals(WsState.CLOSED, c.state)
        assertEquals(1000, c.closeCode)
        assertEquals("done", c.closeReason)
        assertEquals(now, c.closedAtMs)
    }

    /** The twin: the server closes first; the app's echo afterwards does not replace its frame. */
    @Test
    fun `the server's close frame stays when it came first`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        recorder.wsOpened(id)
        recorder.wsClosed(id, 4001, "server says bye")
        recorder.wsClosing(id, 4001, "echo")
        val c = entry(id) as WsConnectionEntry
        assertEquals(4001, c.closeCode)
        assertEquals("server says bye", c.closeReason)
    }

    @Test
    fun `a pushed message is an incoming message with its marker, through the push recorder`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        recorder.wsOpened(id)
        val push = recorder.pushRecorder()
        assertEquals(store.runId, push.runId)
        assertEquals(id, push.connection(id)!!.id)
        val injection = io.yamsergey.dta.protocol.network.WsInjection(
            io.yamsergey.dta.protocol.network.InjectionSource.CLIENT,
            delivery = io.yamsergey.dta.protocol.network.InjectionDelivery.READING_THREAD, waitedMs = 5,
        )
        val m = entry(push.record(id, PushMessage("snap", null), injection)!!) as WsMessageEntry
        assertEquals(Direction.IN, m.direction)
        assertEquals(MessageOpcode.TEXT, m.opcode)
        assertEquals("snap", m.payload.preview)
        assertEquals(injection, m.injected)
        assertNull(m.initiator)
        assertEquals(1, (entry(id) as WsConnectionEntry).messageCount)
    }

    @Test
    fun `a message payload over the bound is truncated, stated`() {
        val small = NetworkRecorder(store, clock = { now }, bodyBound = 4) { _, _ -> }
        val id = small.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        val m = small.wsMessage(id, Direction.IN, MessageOpcode.TEXT, "123456".toByteArray(), null)!!
        val payload = (entry(m) as WsMessageEntry).payload
        assertEquals(Completeness.TRUNCATED, payload.completeness)
        assertEquals(4, payload.storedBytes)
    }

    @Test
    fun `text bodies get a decoded preview, binary bodies are flagged without one`() {
        val id = recorder.httpStarted("okhttp3", "POST", "http://h/a", emptyList(), initiator)
        recorder.httpRequestBody(id, "application/x-protobuf", 4).apply {
            accept(byteArrayOf(10, 0, 1, 2), 0, 4)
            finish(endOfStream = true)
        }
        val zipped = java.io.ByteArrayOutputStream().also { o ->
            java.util.zip.GZIPOutputStream(o).use { it.write("{\"ok\":true}".toByteArray()) }
        }.toByteArray()
        recorder.httpResponse(id, 200, emptyList(), null)
        TeeInputStream(ByteArrayInputStream(zipped), recorder.httpResponseBody(id, "application/json", "gzip", zipped.size.toLong()))
            .use { it.readBytes() }
        val e = entry(id) as HttpEntry
        assertTrue(e.requestBody!!.binary)
        assertNull(e.requestBody!!.preview)
        assertEquals(false, e.responseBody!!.binary)
        assertEquals("{\"ok\":true}", e.responseBody!!.preview)
        assertEquals("gzip", e.responseBody!!.encoding)
    }

    @Test
    fun `a body the app never read has no preview`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        recorder.httpResponse(id, 404, emptyList(), null)
        recorder.httpResponseBody(id, "application/json", null, 14).finish(endOfStream = false)
        assertNull((entry(id) as HttpEntry).responseBody!!.preview)
    }

    @Test
    fun `message payloads get previews by opcode`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        val text = recorder.wsMessage(id, Direction.OUT, MessageOpcode.TEXT, "hello".toByteArray(), initiator)!!
        val binary = recorder.wsMessage(id, Direction.IN, MessageOpcode.BINARY, "hello".toByteArray(), null)!!
        assertEquals("hello", (entry(text) as WsMessageEntry).payload.preview)
        assertTrue((entry(binary) as WsMessageEntry).payload.binary)
        assertNull((entry(binary) as WsMessageEntry).payload.preview)
    }

    @Test
    fun `handshake response headers replace the request's`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", listOf(Header("Upgrade", "websocket")), initiator)
        recorder.wsHandshake(id, listOf(Header("Sec-WebSocket-Accept", "x")))
        assertEquals(listOf(Header("Sec-WebSocket-Accept", "x")), (entry(id) as WsConnectionEntry).handshakeHeaders)
    }

    @Test
    fun `websocket failure is stated`() {
        val id = recorder.wsStarted("nv-websocket", "ws://h/ws", emptyList(), initiator)
        recorder.wsFailed(id, IOException("handshake failed"))
        val c = entry(id) as WsConnectionEntry
        assertEquals(WsState.FAILED, c.state)
        assertEquals("java.io.IOException: handshake failed", c.failure)
    }

    @Test
    fun `calls on unknown ids and failing publishers never throw`() {
        val broken = NetworkRecorder(memory, clock = { now }) { _, _ -> error("publisher down") }
        broken.httpResponse("nope", 200, emptyList(), null)
        broken.httpFailed("nope", IOException())
        broken.wsClosed("nope", 1000, "")
        assertNull(broken.wsMessage("nope", Direction.IN, MessageOpcode.TEXT, byteArrayOf(), null))
        val id = broken.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        assertTrue(memory.get(id) != null, "the entry is stored even when publishing fails")
        assertTrue(broken.errorCount > 0)
    }

    // ---- feature 005: interventions ----

    private val record = io.yamsergey.dta.protocol.network.InterventionRecord(
        "s1", listOf(io.yamsergey.dta.protocol.network.ScriptHandler.REQUEST), listOf(io.yamsergey.dta.protocol.network.ChangedField.HEADERS), delayMs = 5,
    )

    @Test
    fun `an intervention record merges into the entry`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        recorder.recordIntervention(id, record)
        recorder.recordIntervention(id, record.copy(handlers = listOf(io.yamsergey.dta.protocol.network.ScriptHandler.RESPONSE), changed = emptyList(), delayMs = 7))
        val merged = (entry(id) as HttpEntry).intervention!!
        assertEquals(12, merged.delayMs)
        assertEquals(2, merged.handlers.size)
    }

    @Test
    fun `app-side request and response snapshots keep their bodies under the app parts`() {
        val id = recorder.httpStarted("okhttp3", "POST", "http://h/a", emptyList(), initiator)
        recorder.httpAppRequest(id, "http://h/original", "POST", listOf(Header("A", "1")), "original".toByteArray(), "text/plain")
        recorder.httpAppResponse(id, 200, listOf(Header("X", "y")), "patched".toByteArray(), "text/plain")
        val e = entry(id) as HttpEntry
        assertEquals("http://h/original", e.appRequest!!.url)
        assertEquals(BodyPart.APP_REQUEST, e.appRequest!!.body!!.part)
        assertEquals(200, e.appResponse!!.status)
        assertContentEquals("original".toByteArray(), store.openBody(id, BodyPart.APP_REQUEST)!!.readBytes())
        assertContentEquals("patched".toByteArray(), store.openBody(id, BodyPart.APP_RESPONSE)!!.readBytes())
        assertEquals("patched", e.appResponse!!.body!!.preview)
    }

    @Test
    fun `a message's app-side payload is kept`() {
        val conn = recorder.wsStarted("okhttp3-ws", "ws://h", emptyList(), initiator)
        val m = recorder.wsMessage(conn, Direction.OUT, MessageOpcode.TEXT, "v2-hello".toByteArray(), initiator)!!
        recorder.wsAppPayload(m, "v1-hello".toByteArray())
        assertEquals(BodyPart.APP_MESSAGE, (entry(m) as WsMessageEntry).appPayload!!.part)
        assertContentEquals("v1-hello".toByteArray(), store.openBody(m, BodyPart.APP_MESSAGE)!!.readBytes())
    }

    @Test
    fun `mocked and dropped exchanges are entries of their own`() {
        val mocked = recorder.httpMocked("okhttp3", "GET", "http://h/m", emptyList(), initiator, 503, listOf(Header("Retry-After", "5")), "down".toByteArray(), record.copy(mocked = true))
        val m = entry(mocked) as HttpEntry
        assertEquals(HttpState.COMPLETE, m.state)
        assertEquals(503, m.status)
        assertEquals(true, m.intervention!!.mocked)
        assertContentEquals("down".toByteArray(), store.openBody(mocked, BodyPart.RESPONSE)!!.readBytes())

        val dropped = recorder.httpDropped("okhttp3", "GET", "http://h/d", emptyList(), initiator, "dropped by DTA network script s1", record.copy(dropped = true))
        val d = entry(dropped) as HttpEntry
        assertEquals(HttpState.FAILED, d.state)
        assertTrue("s1" in d.failure!!)
        assertEquals(true, d.intervention!!.dropped)
    }

    @Test
    fun `a busy socket does not write its connection again for every message`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", listOf(Header("Upgrade", "websocket")), initiator)
        recorder.wsOpened(id)
        repeat(300) { recorder.wsMessage(id, Direction.IN, MessageOpcode.TEXT, "{\"n\":$it}".toByteArray(), null) }
        assertEquals(300, (entry(id) as WsConnectionEntry).messageCount, "the count is exact")
        store.flushQueuedForTest()
        val written = File(dir, "segments").listFiles()!!.sumOf { f -> f.readLines().count { "\"type\":\"ws-connection\"" in it } }
        assertTrue(written <= 4, "connection records written: $written (one per message before)")
        recorder.wsClosed(id, 1000, "bye")
        val last = store.journal(null, 10_000).records.last { it.id == id } as WsConnectionEntry
        assertEquals(WsState.CLOSED to 300, last.state to last.messageCount)
    }

    @Test
    fun `a small text message all in its preview has no body file, and is read from its record`() {
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        recorder.wsOpened(id)
        val text = "{\"type\": \"tick\", \"symbol\": \"FAST\", \"n\": 1}"
        val m = recorder.wsMessage(id, Direction.IN, MessageOpcode.TEXT, text.toByteArray(), null)!!
        val big = recorder.wsMessage(id, Direction.IN, MessageOpcode.TEXT, "x".repeat(300).toByteArray(), null)!!
        store.flush()
        val files = File(dir, "bodies").listFiles()!!.map { it.name }
        assertTrue(files.none { it.startsWith(m.replace(':', '_')) }, "no file for a message its record holds: $files")
        assertTrue(files.any { it.startsWith(big.replace(':', '_')) }, "a longer one keeps its file")
        val payload = (entry(m) as WsMessageEntry).payload
        assertEquals(text, payload.wholeText())
        assertEquals(text.length.toLong(), store.bodySize(m, BodyPart.MESSAGE))
        assertEquals(text.substring(10, 20), String(store.openBodyRange(m, BodyPart.MESSAGE, 10, 10)!!.readBytes()))
    }
}
