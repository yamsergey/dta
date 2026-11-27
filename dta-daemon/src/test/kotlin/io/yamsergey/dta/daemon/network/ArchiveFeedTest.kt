package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.FeedBatch
import io.yamsergey.dta.protocol.network.FeedEnded
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.Event
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 068 (research R6): the app's events drive the archive; only large bodies are copied by range. */
class ArchiveFeedTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun index(app: FakeJournalApp) = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }

    private var n = 0
    private fun http(app: FakeJournalApp, size: Int): HttpEntry {
        val id = "r1-1:${++n}"
        val body = ByteArray(size) { 'a'.code.toByte() }
        val e = HttpEntry(id, "r1-1", 1_000L + n, "okhttp3", method = "GET", url = "https://api.example.test/v1/$n", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, size.toLong(), contentType = "text/plain", completeness = Completeness.COMPLETE, preview = "a".repeat(minOf(size, 256)).takeIf { size > 256 }))
        app.record(e)
        app.bodies[id to BodyPart.RESPONSE] = body
        return e
    }

    private fun event(batch: FeedBatch) = Event(FeedBatch.EVENT, 1, DtaJson.encodeToJsonElement(FeedBatch.serializer(), batch))

    @Test
    fun `a feed event goes into the archive, and only large bodies are copied by range`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        http(app, 300)
        val host = index(app)
        host.openFeed()
        val small = http(app, 2_000)
        val large = http(app, 70 * 1024)
        host.onAppEvent(event(app.batch(1)))
        assertEquals(listOf(large.id), host.pendingBodies().map { it.entryId }, "the small body came with its record")
        CopyWorker(host).runOnce()
        assertTrue(app.requests.filter { it == "network_body_range" }.isNotEmpty())
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals(setOf(small.id, large.id, "r1-1:1"), host.list(NetworkQuery(limit = 10)).entries.map { it.id }.toSet())
    }

    @Test
    fun `an end, dropped events or another connection end the subscription`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        http(app, 300)
        val host = index(app)
        host.openFeed()
        host.onAppEvent(Event(FeedEnded.EVENT, 2, DtaJson.encodeToJsonElement(FeedEnded.serializer(), FeedEnded(app.feedId!!, FeedEnded.OVERFLOW, "0:1"))))
        assertEquals(false, host.feedLive)
        host.openFeed()
        host.onAppEvent(Event("network.events-dropped", 3, null))
        assertEquals(false, host.feedLive, "a lost batch may be among them")
        host.openFeed()
        host.connectionChanged()
        assertEquals(false, host.feedLive)
    }

    @Test
    fun `a body its preview holds whole is archived when its record comes, never waiting to be copied`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        val host = index(app)
        host.openFeed()
        val id = "r1-1:${++n}"
        app.record(io.yamsergey.dta.protocol.network.WsMessageEntry(id, "r1-1", 5, "okhttp3-ws", connectionId = "r1-1:0",
            direction = io.yamsergey.dta.protocol.network.Direction.IN, opcode = io.yamsergey.dta.protocol.network.MessageOpcode.TEXT,
            payload = BodyInfo(BodyPart.MESSAGE, 4, completeness = Completeness.COMPLETE, preview = "tick")))
        host.onAppEvent(event(app.batch(0)))
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals("tick", host.storedBody(id, BodyPart.MESSAGE)?.second?.decodeToString())
        // 070 E13: the record holds it; no file is written for it (a hash, a file and two rows per message held the catch-up).
        val store = stores.single()
        assertNull(store.bodyRef(store.liveEpoch("dev1", "io.example")!!.id, id, BodyPart.MESSAGE), "no copy beside the record")
        assertEquals(0, File(dir, "bodies").walkTopDown().count { it.isFile }, "no body file")
    }

    /** A channel whose journal pages take [ms] each: catching up takes a while. */
    private class SlowPages(private val ms: Long) : FakeJournalApp() {
        override suspend fun requestFull(method: String, params: kotlinx.serialization.json.JsonObject?, timeout: kotlin.time.Duration?): io.yamsergey.dta.daemon.connection.RpcResult {
            if (method == "network_journal") kotlinx.coroutines.delay(ms)
            return super.requestFull(method, params, timeout)
        }
    }

    /**
     * D-125: a channel whose journal pages come until [gateAt] of them were served, then waits for [release]: the test reads the
     * catch-up's state at a known place, not after a sleep that a slow machine can miss.
     */
    private class GatedPages : FakeJournalApp() {
        @Volatile var served = 0
        @Volatile var gateAt = Int.MAX_VALUE
        val blocked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun requestFull(method: String, params: kotlinx.serialization.json.JsonObject?, timeout: kotlin.time.Duration?): io.yamsergey.dta.daemon.connection.RpcResult {
            if (method == "network_journal") {
                if (++served > gateAt) { blocked.complete(Unit); release.await() }
            }
            return super.requestFull(method, params, timeout)
        }
    }

    @Test
    fun `while it catches up, the history says how far it is at once, without waiting for it`() = runBlocking<Unit> {
        val app = SlowPages(ms = 300)
        repeat(3_000) { http(app, 10) }
        val host = index(app)
        host.pageSize = 500
        val catching = this.async(kotlinx.coroutines.Dispatchers.IO) { host.list(NetworkQuery(limit = 1)) }
        kotlinx.coroutines.delay(800)
        val started = System.currentTimeMillis()
        val h = host.history()
        assertTrue(System.currentTimeMillis() - started < 200, "answered at once")
        val behind = h.device?.catchingUp
        assertTrue(behind != null && behind in 1..2_999, "how far: $behind")
        catching.await()
        repeat(5) { host.list(NetworkQuery(limit = 1)) }
        assertEquals(null, host.history().device?.catchingUp, "caught up")
    }

    @Test
    fun `the history asked first does not wait for the catch-up it starts`() = runBlocking<Unit> {
        val app = SlowPages(ms = 300)
        repeat(3_000) { http(app, 10) }
        val host = index(app)
        host.pageSize = 500
        host.list(NetworkQuery(limit = 1))
        repeat(3_000) { http(app, 10) }
        val started = System.currentTimeMillis()
        host.history()
        assertTrue(System.currentTimeMillis() - started < 1_000, "answered without the catch-up: ${System.currentTimeMillis() - started} ms")
        var seen: Long? = null
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val behind = host.history().device?.catchingUp
            if (behind != null) seen = behind
            if (seen != null && behind == null) break
            kotlinx.coroutines.delay(100)
        }
        assertTrue(seen != null && seen in 1..3_000, "how far was said while it ran: $seen")
        assertEquals(null, host.history().device?.catchingUp, "and it ended by itself")
        assertEquals(6_000, host.list(NetworkQuery(limit = 1)).coverage.searched, "every entry is in the history")
    }

    /**
     * Found in the robot IDE (070): while the feed caught up 600k entries, a search waited for the
     * catch-up's 40 pages and then read 40 more itself (~15 s). The catch-up holds the archive one
     * page at a time, and a query beside it reads one page at most: it answers from what is archived,
     * saying how far behind.
     */
    @Test
    fun `a query while the feed catches up answers after a page or two, saying how far behind`() = runBlocking<Unit> {
        val app = SlowPages(ms = 100)
        // Ticks, as on the emulator: each message's body is its preview, archived with its record.
        repeat(20_000) {
            val id = "r1-1:${++n}"
            app.record(io.yamsergey.dta.protocol.network.WsMessageEntry(id, "r1-1", 1_000L + n, "okhttp3-ws", connectionId = "r1-1:0",
                direction = io.yamsergey.dta.protocol.network.Direction.IN, opcode = io.yamsergey.dta.protocol.network.MessageOpcode.TEXT,
                payload = BodyInfo(BodyPart.MESSAGE, 9, completeness = Completeness.COMPLETE, preview = "tick $n".padEnd(9).take(9))))
        }
        val host = index(app)
        host.pageSize = 500
        val catching = this.async(kotlinx.coroutines.Dispatchers.IO) { host.openFeed() }
        kotlinx.coroutines.delay(450)
        for (ask in listOf<suspend () -> Unit>(
            { assertTrue((host.list(NetworkQuery(limit = 1)).coverage.hostLagRecords ?: 0) in 1..19_999, "behind is said") },
            { host.search(NetworkQuery(q = "tick", limit = 1)) },
        )) {
            val started = System.currentTimeMillis()
            ask()
            val took = System.currentTimeMillis() - started
            assertTrue(took < 700, "answered beside the catch-up in $took ms")
        }
        catching.await()
        while (host.openFeed() == null) Unit
        assertTrue(host.feedLive)
        assertEquals(20_000, host.list(NetworkQuery(limit = 1)).coverage.searched, "every entry is archived")
    }

    @Test
    fun `a clear while the feed catches up still copies the whole journal first`() = runBlocking<Unit> {
        val app = SlowPages(ms = 20)
        repeat(5_000) {
            val id = "r1-1:${++n}"
            app.record(io.yamsergey.dta.protocol.network.WsMessageEntry(id, "r1-1", 1_000L + n, "okhttp3-ws", connectionId = "r1-1:0",
                direction = io.yamsergey.dta.protocol.network.Direction.IN, opcode = io.yamsergey.dta.protocol.network.MessageOpcode.TEXT,
                payload = BodyInfo(BodyPart.MESSAGE, 4, completeness = Completeness.COMPLETE, preview = "tick")))
        }
        val host = index(app)
        host.pageSize = 100
        host.catchUpBudgetPages = 5
        val catching = this.async(kotlinx.coroutines.Dispatchers.IO) { host.openFeed() }
        kotlinx.coroutines.delay(50)
        assertEquals(null, host.copyBeforeClear(timeoutMs = 20_000), "nothing left behind")
        catching.await()
        assertEquals(5_000, host.list(NetworkQuery(limit = 1)).coverage.searched)
    }

    @Test
    fun `how far the catch-up is comes from the device, also when it removed what was archived`() = runBlocking<Unit> {
        val app = GatedPages()
        repeat(1_000) { http(app, 10) }
        val host = index(app)
        host.pageSize = 500
        host.list(NetworkQuery(limit = 1))
        repeat(3_000) { http(app, 10) }
        // The device removed what this computer archived: it holds fewer entries than were read.
        app.entriesOverride = 2_500
        // The catch-up serves two pages (1,000 of the 3,000 more), then waits: its state is read there, not after a sleep (D-125).
        app.gateAt = app.served + 2
        val catching = this.async(kotlinx.coroutines.Dispatchers.IO) { host.list(NetworkQuery(limit = 1)) }
        withTimeout(10_000) { app.blocked.await() }
        val behind = host.history().device?.catchingUp
        assertTrue(behind != null && behind in 1_500..2_500, "records left after the page read: $behind")
        app.release.complete(Unit)
        catching.await()
    }
}
