package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.MatchLocation
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import io.yamsergey.dta.sidekick.network.store.StoreBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkMethodsTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private var queueCapacity = 10_000
    private var writerOn = true
    private val store by lazy {
        NetworkStore(StorageDir(dir), pid = 7, clock = { now++ }, queueCapacity = queueCapacity, startWriter = writerOn)
    }
    private val recorder by lazy { NetworkRecorder(store, clock = { now }) { _, _ -> } }
    private var status = CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)
    private var scriptCodes = emptyList<String>()
    private val methods by lazy { NetworkMethods(store, { scriptCodes }) { status } }
    private val initiator = Initiator("main", InitiatorSite.CALL, frames = emptyList())

    @AfterEach
    fun close() = store.close()

    private fun params(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `list returns summaries without headers, with coverage`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", listOf(Header("A", "b")), initiator)
        val page = methods.list(null)
        val entry = page.entries.single() as HttpEntry
        assertEquals(id, entry.id)
        assertNull(entry.requestHeaders)
        assertEquals(1, page.total)
        assertEquals(1, page.coverage.stored)
        assertEquals(1, page.coverage.searched)
        assertNull(page.degraded)
    }

    @Test
    fun `list honours limit, order and cursor`() {
        val ids = (1..3).map { recorder.httpStarted("okhttp3", "GET", "http://h/$it", emptyList(), initiator) }
        val first = methods.list(params("""{"limit":2,"order":"oldest-first"}"""))
        assertEquals(ids.take(2), first.entries.map { it.id })
        assertEquals(ids[1], first.next)
        val second = methods.list(params("""{"limit":2,"order":"oldest-first","before":"${first.next}"}"""))
        assertEquals(listOf(ids[2]), second.entries.map { it.id })
    }

    @Test
    fun `invalid params are rejected`() {
        assertFailsWith<InvalidParamsException> { methods.list(params("""{"limit":0}""")) }
    }

    @Test
    fun `entry returns full detail and unknown ids name the earliest stored time`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", listOf(Header("A", "b")), initiator)
        val detail = methods.entry(params("""{"id":"$id"}"""))
        assertEquals(listOf(Header("A", "b")), (detail.entry as HttpEntry).requestHeaders)

        val e = assertFailsWith<InvalidParamsException> { methods.entry(params("""{"id":"r0-0:1"}""")) }
        assertTrue("unknown entry id r0-0:1" in e.message!!, e.message)
        assertTrue("earliest stored" in e.message!!, e.message)
        assertFailsWith<InvalidParamsException> { methods.entry(null) }
    }

    @Test
    fun `a connection's detail lists its messages in order`() {
        val conn = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        val other = recorder.wsStarted("okhttp3-ws", "ws://h/other", emptyList(), initiator)
        val m1 = recorder.wsMessage(conn, Direction.OUT, MessageOpcode.TEXT, "a".toByteArray(), initiator)
        recorder.wsMessage(other, Direction.OUT, MessageOpcode.TEXT, "x".toByteArray(), initiator)
        val m2 = recorder.wsMessage(conn, Direction.IN, MessageOpcode.TEXT, "b".toByteArray(), null)
        val detail = methods.entry(params("""{"id":"$conn"}"""))
        assertTrue(detail.entry is WsConnectionEntry)
        assertEquals(listOf(m1, m2), detail.messages!!.map { it.id })
        assertNull(detail.messagesTruncated)
    }

    @Test
    fun `clear empties history and states when`() {
        recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        val result = methods.clear()
        assertEquals(1, result.clearedEntries)
        val page = methods.list(null)
        assertTrue(page.entries.isEmpty())
        assertEquals(result.clearedAtMs, page.coverage.clearedAtMs)
    }

    @Test
    fun `an entry whose initiator is missing is stated on the page and detail`() {
        val missing = Initiator("OkHttp Dispatcher", InitiatorSite.CALL, missing = "stack capture failed")
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), missing)
        assertTrue(DegradationCodes.NETWORK_INITIATOR_MISSING in methods.list(null).degraded!!.map { it.reason })
        assertTrue(DegradationCodes.NETWORK_INITIATOR_MISSING in methods.entry(params("""{"id":"$id"}""")).degraded!!.map { it.reason })
    }

    @Test
    fun `entries with stacks raise no initiator marker`() {
        val withStack = Initiator("main", InitiatorSite.CALL, frames = emptyList())
        recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), withStack)
        assertNull(methods.list(null).degraded)
    }

    @Test
    fun `dropped events and unavailable capture are stated on the page`() {
        queueCapacity = 2
        writerOn = false
        repeat(3) { recorder.httpStarted("okhttp3", "GET", "http://h/$it", emptyList(), initiator) }
        status = CapabilityStatus("network", CapabilityStatus.Status.UNAVAILABLE, "requires API 28+ (device is API 26)")
        val reasons = methods.list(null).degraded!!.map { it.reason }
        assertTrue(DegradationCodes.NETWORK_EVENTS_DROPPED in reasons, "$reasons")
        assertTrue(DegradationCodes.NETWORK_HOOKS_UNAVAILABLE in reasons, "$reasons")
        status = CapabilityStatus("network", CapabilityStatus.Status.DEGRADED, "okhttp3 hook failed")
        assertTrue(DegradationCodes.NETWORK_LIBRARY_UNAVAILABLE in methods.list(null).degraded!!.map { it.reason })
    }

    @Test
    fun `body streams stored wire bytes in 256 KiB chunks`() {
        val id = recorder.httpStarted("okhttp3", "POST", "http://h/a", emptyList(), initiator)
        val bytes = ByteArray(600 * 1024) { (it * 31 + 7).toByte() }
        recorder.httpRequestBody(id, "application/x-protobuf", bytes.size.toLong()).apply {
            accept(bytes, 0, bytes.size)
            finish(endOfStream = true)
        }
        val chunks = mutableListOf<ByteArray>()
        val result = methods.body(params("""{"id":"$id","part":"request"}""")) { chunks += it }
        assertEquals(bytes.size.toLong(), result.body.storedBytes)
        assertEquals(listOf(256 * 1024, 256 * 1024, 88 * 1024), chunks.map { it.size })
        assertContentEquals(bytes, chunks.reduce { a, b -> a + b })
    }

    @Test
    fun `a body whose file is gone is reported dropped with zero frames`() {
        val id = recorder.httpStarted("okhttp3", "POST", "http://h/a", emptyList(), initiator)
        recorder.httpRequestBody(id, "text/plain", 1).apply { accept(byteArrayOf(1), 0, 1); finish(endOfStream = true) }
        File(dir, "bodies").listFiles()!!.forEach { it.delete() }
        val chunks = mutableListOf<ByteArray>()
        val result = methods.body(params("""{"id":"$id","part":"request"}""")) { chunks += it }
        assertEquals(Completeness.DROPPED, result.body.completeness)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `body of a missing part or unknown entry is invalid params`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        val e = assertFailsWith<InvalidParamsException> { methods.body(params("""{"id":"$id","part":"request"}""")) {} }
        assertTrue("no request body" in e.message!!, e.message)
        assertFailsWith<InvalidParamsException> { methods.body(params("""{"id":"$id","part":"sideways"}""")) {} }
        assertFailsWith<InvalidParamsException> { methods.body(params("""{"id":"r0-0:9","part":"response"}""")) {} }
    }

    @Test
    fun `message payloads are fetched with part message`() {
        val conn = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        val m = recorder.wsMessage(conn, Direction.IN, MessageOpcode.BINARY, byteArrayOf(4, 5, 6), null)
        val chunks = mutableListOf<ByteArray>()
        methods.body(params("""{"id":"$m","part":"message"}""")) { chunks += it }
        assertContentEquals(byteArrayOf(4, 5, 6), chunks.single())
    }

    @Test
    fun `filters narrow the list with match locations, searched count and what was not searched`() {
        val auth = recorder.httpStarted("okhttp3", "POST", "http://h/auth", emptyList(), initiator)
        recorder.httpRequestBody(auth, "application/json", null).apply {
            val b = """{"account_id":"A-1"}""".toByteArray(); accept(b, 0, b.size); finish(endOfStream = true)
        }
        val other = recorder.httpStarted("okhttp3", "POST", "http://h/other", emptyList(), initiator)
        recorder.httpRequestBody(other, "application/x-protobuf", null).apply {
            val b = "account_id".toByteArray(); accept(b, 0, b.size); finish(endOfStream = true)
        }
        val conn = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), initiator)
        val msg = recorder.wsMessage(conn, Direction.IN, MessageOpcode.TEXT, """{"account_id":"A-2"}""".toByteArray(), null)!!

        val page = methods.list(params("""{"filter":{"bodyContains":"account_id"},"order":"oldest-first"}"""))
        assertEquals(listOf(auth, other, msg), page.entries.map { it.id })
        assertEquals(3, page.total)
        assertEquals(4, page.coverage.searched)
        assertEquals(listOf(MatchLocation.REQUEST_BODY), page.matches!![auth])
        assertEquals(listOf(MatchLocation.REQUEST_BODY_BINARY), page.matches!![other], "found inside the protobuf body")
        assertEquals(listOf(MatchLocation.PAYLOAD), page.matches!![msg])
        assertNull(page.coverage.notSearched, "binary bodies are searched")

        val brotli = recorder.httpStarted("okhttp3", "POST", "http://h/br", emptyList(), initiator)
        recorder.httpRequestBody(brotli, "application/x-protobuf", null, encoding = "br").apply {
            val b = "account_id".toByteArray(); accept(b, 0, b.size); finish(endOfStream = true)
        }
        val withBrotli = methods.list(params("""{"filter":{"bodyContains":"account_id"}}"""))
        assertTrue(brotli !in withBrotli.entries.map { it.id })
        assertEquals(listOf("binary bodies whose encoding cannot be undone (1)"), withBrotli.coverage.notSearched)

        val none = methods.list(params("""{"filter":{"url":"nothing-here"}}"""))
        assertTrue(none.entries.isEmpty())
        assertEquals(0, none.total)
        assertEquals(5, none.coverage.searched)
        assertNull(none.matches)
    }

    @Test
    fun `gzip bodies are searched decoded`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/z", emptyList(), initiator)
        val zipped = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write("needle".toByteArray()) } }.toByteArray()
        recorder.httpResponse(id, 200, emptyList(), null)
        recorder.httpResponseBody(id, "text/plain", "gzip", null).apply { accept(zipped, 0, zipped.size); finish(endOfStream = true) }
        assertEquals(listOf(id), methods.list(params("""{"filter":{"bodyContains":"needle"}}""")).entries.map { it.id })
    }

    @Test
    fun `a page reaching the evicted boundary says history was evicted`() {
        val small = NetworkStore(StorageDir(File(dir, "small")), pid = 8, clock = { now++ }, bounds = StoreBounds(historyBytes = 8_000, segmentBytes = 500))
        try {
            val rec = NetworkRecorder(small, clock = { now }) { _, _ -> }
            val ids = (1..6).map { i ->
                rec.httpStarted("okhttp3", "GET", "http://h/$i", emptyList(), initiator).also { id ->
                    rec.httpResponse(id, 200, emptyList(), null)
                    rec.httpResponseBody(id, "text/plain", null, null).apply { accept(ByteArray(800), 0, 800); finish(endOfStream = true) }
                    small.flush()
                }
            }
            small.evictIfNeeded()
            val m = NetworkMethods(small) { status }
            val oldest = m.list(params("""{"order":"oldest-first","limit":1}"""))
            assertTrue(DegradationCodes.NETWORK_HISTORY_EVICTED in oldest.degraded!!.map { it.reason }, "${oldest.degraded}")
            assertTrue(oldest.coverage.evicted > 0)
            val newest = m.list(params("""{"limit":1}"""))
            assertNull(newest.degraded, "the newest page does not reach the evicted range")
            assertEquals(ids.last(), newest.entries.single().id)
        } finally {
            small.close()
        }
    }

    @Test
    fun `memory-only history is stated`() {
        val memory = NetworkStore(null, pid = 9, clock = { now++ })
        val m = NetworkMethods(memory) { status }
        assertTrue(DegradationCodes.NETWORK_HISTORY_NOT_PERSISTED in m.list(null).degraded!!.map { it.reason })
    }

    @Test
    fun `script degradations are stated on pages and details`() {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/a", emptyList(), initiator)
        scriptCodes = listOf(DegradationCodes.NETWORK_SCRIPT_NOT_PERSISTED)
        assertTrue(DegradationCodes.NETWORK_SCRIPT_NOT_PERSISTED in methods.list(null).degraded!!.map { it.reason })
        assertTrue(DegradationCodes.NETWORK_SCRIPT_NOT_PERSISTED in methods.entry(params("""{"id":"$id"}""")).degraded!!.map { it.reason })
    }
}
