package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 067: a live view's answers equal the plain answers whatever it reuses (the same query, a
 * recent one, a narrower one), while traffic arrives and bodies are copied; opening an entry never
 * waits for a pass; a newer query of a view abandons the running pass; live decisions equal the list.
 */
class HostIndexLiveViewTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun index(app: FakeJournalApp, readEnvelope: (WsMessageEntry, String) -> io.yamsergey.dta.protocol.network.Envelope? = { _, text -> io.yamsergey.dta.protocol.network.WsFormats.detect(text) }) =
        HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example", readEnvelope = readEnvelope) { app }

    private val socket = WsConnectionEntry("r1-1:0", "r1-1", 1, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
    private val words = listOf("tick", "tock", "price", "prize", "fast", "cart", "boom", "id4271")
    private var n = 0

    /** A socket message whose payload this computer copies only when [copyNow] (else it is searched by its preview). */
    private fun message(app: FakeJournalApp, r: Random, wholePreview: Boolean = false): WsMessageEntry {
        val text = "{\"type\":\"${words.random(r)}\",\"v\":\"${words.random(r)} ${words.random(r)}\"}"
        val m = WsMessageEntry("r1-1:${++n}", "r1-1", 1_000L + n, "okhttp3-ws", connectionId = socket.id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
            opcode = MessageOpcode.TEXT, payload = BodyInfo(BodyPart.MESSAGE, text.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = if (wholePreview) text else text.take(25)))
        app.record(m)
        app.bodies[m.id to BodyPart.MESSAGE] = text.toByteArray()
        return m
    }

    private fun plain(p: NetworkPage) = p.copy(coverage = p.coverage.copy(bodiesPending = null))

    @Test
    fun `answers through a view equal the plain answers, while traffic arrives and bodies are copied`() = runBlocking<Unit> {
        repeat(8) { seed ->
            val app = FakeJournalApp().apply { record(socket) }
            val host = index(app)
            val r = Random(seed)
            repeat(80) { message(app, r) }
            val typing = listOf("t", "ti", "tic", "tick", "tick fast", "tick", "tic", "tick", "dir:sent tick", "cart", "ca", "cart", "id42", "id4271", "boom -dir:received")
            for (q in typing) {
                repeat(r.nextInt(0, 6)) { message(app, r) }
                // Some bodies arrive here between two queries: a preview decision is read again.
                host.pendingBodies().filter { r.nextInt(3) == 0 }.forEach { host.copyBody(it.entryId, it.part) }
                val query = NetworkQuery(q = q, limit = 30)
                val viewed = host.list(query, "v")
                val kind = host.lastPass
                val reference = host.list(query)
                assertEquals(plain(reference), plain(viewed), "seed $seed, '$q' ($kind)")
                val searched = host.search(query.copy(limit = 50), "v")
                assertEquals(host.search(query.copy(limit = 50)), searched, "search, seed $seed, '$q'")
            }
        }
    }

    @Test
    fun `the same query, a recent one and a narrower one are not full passes`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { record(socket) }
        val host = index(app)
        val r = Random(1)
        repeat(50) { message(app, r) }
        host.list(NetworkQuery(q = "ti"), "v")
        assertEquals(LiveViews.Kind.FULL, host.lastPass)
        host.list(NetworkQuery(q = "tick"), "v")
        assertEquals(LiveViews.Kind.NARROWED, host.lastPass)
        host.list(NetworkQuery(q = "tick"), "v")
        assertEquals(LiveViews.Kind.SAME, host.lastPass)
        host.list(NetworkQuery(q = "ti"), "v")
        assertEquals(LiveViews.Kind.RECENT, host.lastPass, "Backspace: the set of 'ti' is reused")
        host.list(NetworkQuery(q = "price"), "v")
        assertEquals(LiveViews.Kind.FULL, host.lastPass)
        host.list(NetworkQuery(q = "tick", redact = true), "v")
        assertEquals(LiveViews.Kind.FULL, host.lastPass, "redaction is never reused")
    }

    @Test
    fun `opening an entry does not wait for a pass`() = runBlocking<Unit> {
        val inPass = CountDownLatch(1)
        val release = CountDownLatch(1)
        val app = FakeJournalApp().apply { record(socket) }
        val host = index(app, readEnvelope = { _, text ->
            inPass.countDown()
            release.await(10, TimeUnit.SECONDS)
            io.yamsergey.dta.protocol.network.WsFormats.detect(text)
        })
        val r = Random(2)
        repeat(20) { message(app, r) }
        host.copyAllPending()
        val pass = async(Dispatchers.IO) { host.list(NetworkQuery(q = "type:tick"), "v") }
        assertTrue(inPass.await(10, TimeUnit.SECONDS), "the pass started")
        val detail = withTimeout(3_000) { host.entry("r1-1:1") }
        assertEquals("r1-1:1", detail.entry.id)
        release.countDown()
        pass.await()
    }

    @Test
    fun `a newer query of a view abandons the running pass`() = runBlocking<Unit> {
        val inPass = CountDownLatch(1)
        val release = CountDownLatch(1)
        val app = FakeJournalApp().apply { record(socket) }
        var block = true
        val host = index(app, readEnvelope = { _, text ->
            if (block) { inPass.countDown(); release.await(10, TimeUnit.SECONDS) }
            io.yamsergey.dta.protocol.network.WsFormats.detect(text)
        })
        val r = Random(3)
        repeat(20) { message(app, r) }
        host.copyAllPending()
        val first = async(Dispatchers.IO) { runCatching { host.list(NetworkQuery(q = "type:tick"), "v") } }
        assertTrue(inPass.await(10, TimeUnit.SECONDS))
        block = false
        host.list(NetworkQuery(q = "tock"), "v")
        release.countDown()
        val e = first.await().exceptionOrNull()
        assertTrue(e is HostIndex.SupersededException, "the older pass gave way: $e")
    }

    @Test
    fun `a live decision is what the list says, and an entry that stops matching has left`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { record(socket) }
        val host = index(app)
        val r = Random(4)
        repeat(60) { message(app, r) }
        val q = NetworkQuery(q = "tick")
        host.list(q.copy(limit = 1000), "v")
        // New traffic, decided one by one before it is synced (its preview is all of it, as for small messages).
        repeat(30) {
            val m = message(app, r, wholePreview = true)
            val (d, had) = host.decide("v", q, m) { null }
            assertEquals(false, had)
            val now = host.list(q.copy(limit = 1000)).entries.map { it.id }.toSet()
            assertEquals(m.id in now, d.match, m.id)
        }
        val out = NetworkQuery(q = "dir:sent")
        val gone = host.list(out.copy(limit = 1000), "v").entries.first().id
        val before = app.records.last { it.id == gone } as WsMessageEntry
        val changed = before.copy(direction = Direction.IN)
        app.record(changed)
        val (d, had) = host.decide("v", out, changed) { null }
        assertEquals(false, d.match)
        assertEquals(true, had, "it was in the view's matches: it has left")
    }

    /** A channel where every body range takes [ms]: a large backlog of bodies takes long to copy. */
    private class SlowBodies(private val ms: Long) : FakeJournalApp() {
        override suspend fun requestFull(method: String, params: kotlinx.serialization.json.JsonObject?, timeout: kotlin.time.Duration?): io.yamsergey.dta.daemon.connection.RpcResult {
            if (method == "network_body_range") kotlinx.coroutines.delay(ms)
            return super.requestFull(method, params, timeout)
        }
    }

    @Test
    fun `a search copies bodies for a bounded time, newest first, and says how many it read only in part`() = runBlocking<Unit> {
        val app = SlowBodies(ms = 40).apply { record(socket) }
        val host = index(app)
        host.searchCopyMs = 300
        val r = Random(5)
        val sent = List(80) { message(app, r) }
        host.list(NetworkQuery(limit = 1)) // catches up; no search words, so nothing is copied
        assertEquals(80, host.pendingBodies().size)
        val started = System.currentTimeMillis()
        val page = host.list(NetworkQuery(q = "tick", limit = 30), "v")
        assertTrue(System.currentTimeMillis() - started < 2_000, "the answer did not wait for every body")
        val left = host.pendingBodies().map { it.entryId }.toSet()
        assertTrue(left.size in 1 until 80, "some copied, the rest left to the copy worker: ${left.size}")
        assertEquals(left.size, page.coverage.bodiesPending, "the answer says how many bodies it read only in part")
        val copied = sent.map { it.id }.filter { it !in left }
        assertEquals(sent.takeLast(copied.size).map { it.id }, copied, "the newest first: the first page is the newest")
        // Once the rest is copied, the answer is the whole one.
        host.searchCopyMs = 60_000
        val whole = host.list(NetworkQuery(q = "tick", limit = 30), "v")
        assertEquals(null, whole.coverage.bodiesPending)
        assertEquals(plain(host.list(NetworkQuery(q = "tick", limit = 30))), plain(whole))
    }

    @Test
    fun `a body whose preview is all of it is searched as the body, with nothing copied first`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { record(socket) }
        val host = index(app)
        val r = Random(6)
        repeat(40) { message(app, r, wholePreview = true) }
        val q = NetworkQuery(q = "tick", limit = 1000)
        val page = host.list(q, "v")
        assertEquals(0, app.requests.count { it == "network_body_range" }, "small messages need no copy to be searched")
        assertEquals(null, host.search(q).notSearched, "read as the body, not as a preview")
        // The same answer as with every body copied.
        host.copyAllPending()
        assertEquals(plain(page).entries, plain(host.list(q)).entries)
        assertEquals(page.hits, host.list(q).hits)
    }

    @Test
    fun `a body all in its preview is archived from the preview, with nothing asked of the app`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { record(socket) }
        val host = index(app)
        val r = Random(8)
        val sent = List(20) { message(app, r, wholePreview = true) }
        // The device keeps no file for them (the record holds them): asking would fail.
        app.bodies.clear()
        host.list(NetworkQuery(limit = 1))
        host.copyAllPending()
        assertEquals(0, app.requests.count { it == "network_body_range" })
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals(null, host.list(NetworkQuery(limit = 1)).coverage.bodiesNotArchived, "archived, not lost")
        val m = sent.first()
        assertEquals(m.payload.preview, host.storedBody(m.id, BodyPart.MESSAGE)?.second?.decodeToString())
    }

    /** A channel whose body ranges wait until released, or fail on this computer's side. */
    private class StuckBodies : FakeJournalApp() {
        val entered = CountDownLatch(1)
        var fail: Exception? = null
        override suspend fun requestFull(method: String, params: kotlinx.serialization.json.JsonObject?, timeout: kotlin.time.Duration?): io.yamsergey.dta.daemon.connection.RpcResult {
            if (method == "network_body_range") {
                fail?.let { throw it }
                entered.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
            return super.requestFull(method, params, timeout)
        }
    }

    @Test
    fun `a copy that is cancelled or fails on this computer leaves the body to copy later, not lost`() = runBlocking<Unit> {
        val app = StuckBodies().apply { record(socket) }
        val host = index(app)
        val m = message(app, Random(7))
        host.list(NetworkQuery(limit = 1))
        // Cancelled (an answer gave way, the daemon stops): the app still has the body.
        val copy = launch(Dispatchers.IO) { host.copyBody(m.id, BodyPart.MESSAGE) }
        assertTrue(app.entered.await(10, TimeUnit.SECONDS))
        copy.cancelAndJoin()
        assertEquals(listOf(m.id), host.pendingBodies().map { it.entryId }, "still to copy")
        assertEquals(null, host.list(NetworkQuery(limit = 1)).coverage.bodiesNotArchived, "not said lost")
        // A failure here (a file of this computer) is not the app losing it either.
        app.fail = java.io.FileNotFoundException("partial file")
        host.copyBody(m.id, BodyPart.MESSAGE)
        assertEquals(listOf(m.id), host.pendingBodies().map { it.entryId })
        assertEquals(null, host.list(NetworkQuery(limit = 1)).coverage.bodiesNotArchived)
        // Only the app saying it has no such body makes it lost.
        app.fail = io.yamsergey.dta.daemon.connection.SidekickRpcException(-32602, "no stored message body for ${m.id}")
        host.copyBody(m.id, BodyPart.MESSAGE)
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals(1, host.list(NetworkQuery(limit = 1)).coverage.bodiesNotArchived)
    }

    @Test
    fun `match sets are bounded per view and in all`() {
        val views = LiveViews(perView = 2, total = 3)
        val state = HostIndex.LiveState("e1", 0, 0, { null }, emptySet())
        for (v in listOf("a", "b")) for (q in listOf("x", "y", "z")) views.record(v, NetworkQuery(q = q), state, setOf("1"))
        assertTrue(views.size() <= 3)
    }

    private suspend fun HostIndex.copyAllPending() = pendingBodies().forEach { copyBody(it.entryId, it.part) }
}
