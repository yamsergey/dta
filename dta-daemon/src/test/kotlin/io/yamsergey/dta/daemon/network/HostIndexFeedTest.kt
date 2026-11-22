package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Feature 068 (research R1, R5, R6): this computer's archive fed by the app's journal as one feed.
 * Pages carry small bodies; after reading to the end it subscribes from there; a batch is applied
 * only from the position it holds; a gap or an end makes it read pages again; nothing is lost or
 * doubled whatever happens on the way (SC-003).
 */
class HostIndexFeedTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun index(app: FakeJournalApp, root: File = dir) =
        HostIndex(HostStore(root).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }

    private var n = 0
    private fun http(app: FakeJournalApp, body: String? = "{\"n\":${n + 1},\"pad\":\"${"x".repeat(300)}\"}", state: HttpState = HttpState.COMPLETE): HttpEntry {
        val id = "r1-1:${++n}"
        val e = HttpEntry(id, "r1-1", 1_000L + n, "okhttp3", method = "GET", url = "https://api.example.test/v1/$n", state = state, status = 200,
            responseBody = body?.let { BodyInfo(BodyPart.RESPONSE, it.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = it.take(256)) })
        app.record(e)
        body?.let { app.bodies[id to BodyPart.RESPONSE] = it.toByteArray() }
        return e
    }

    private fun plain(p: NetworkPage) = p.copy(coverage = p.coverage.copy(bodiesPending = null, bodiesNotArchived = null))

    @Test
    fun `pages carry small bodies, so nothing is asked body by body`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        val e = http(app)
        val host = index(app)
        host.list(NetworkQuery())
        assertEquals(0, app.requests.count { it == "network_body_range" })
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals(app.bodies[e.id to BodyPart.RESPONSE]!!.decodeToString(), host.storedBody(e.id, BodyPart.RESPONSE)?.second?.decodeToString())
    }

    /**
     * fix-g (D-004/D-010/D-015): the first answer after a start dials the app under the mutex; a catch-up
     * that begins meanwhile must not make that answer read nothing and say it is caught up.
     */
    @Test
    fun `an answer that dialled while the catch-up started reads the journal or says it is behind`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(30) { http(app) }
        val dialling = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dialled = kotlinx.coroutines.CompletableDeferred<Unit>()
        var first = true
        val host = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") {
            if (first) { first = false; dialling.complete(Unit); dialled.await() }
            app
        }
        host.pageSize = 10
        host.readInBackground()
        val answer = async(kotlinx.coroutines.Dispatchers.Default) { host.list(NetworkQuery(limit = 100)) }
        dialling.await()
        val feed = launch(kotlinx.coroutines.Dispatchers.Default) { host.openFeed() }
        kotlinx.coroutines.delay(200) // the catch-up has begun and waits for the answer's hold
        dialled.complete(Unit)
        val page = answer.await()
        feed.join()
        assertTrue(page.entries.isNotEmpty() || (page.coverage.hostLagRecords ?: 0) > 0,
            "nothing read and nothing said: ${page.entries.size} entries, lag ${page.coverage.hostLagRecords}")
    }

    /** D-009: an entry the app wrote that its batch has not brought yet is the app's to answer, not "evicted". */
    @Test
    fun `an entry asked for before its batch arrives is answered`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        repeat(3) { http(app) }
        val host = index(app)
        assertNotNull(host.openFeed()?.subscription)
        val e = http(app) // written; its batch is still on the way
        assertEquals(e.id, host.entry(e.id).entry.id)
    }

    /** D-009: the app restarted on a new channel before the background loop noticed; its feed is gone with the old one. */
    @Test
    fun `a subscription of an earlier connection does not stand for the app now connected`() = runBlocking<Unit> {
        val first = FakeJournalApp().apply { attachBodies = true }
        repeat(3) { http(first) }
        var app = first
        val host = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }
        assertNotNull(host.openFeed()?.subscription)
        // The same journal, now read by a new process over a new channel.
        val second = FakeJournalApp().apply { attachBodies = true; records += first.records; bodies += first.bodies }
        app = second
        val e = http(second)
        assertEquals(listOf(e.id) + first.records.map { it.id }.reversed(), host.list(NetworkQuery(limit = 100)).entries.map { it.id })
    }

    @Test
    fun `once read to the end it subscribes from there, and batches from that position go in`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        repeat(3) { http(app) }
        val host = index(app)
        val opened = assertNotNull(host.openFeed()?.subscription)
        assertEquals("0:3", app.feedFrom)
        repeat(4) { http(app) }
        assertEquals(HostIndex.Applied.APPLIED, host.apply(app.batch(3)))
        assertEquals(plain(app.deviceAnswer(NetworkQuery(limit = 100))).entries, plain(host.list(NetworkQuery(limit = 100))).entries)
        assertEquals(1, app.requests.count { it == "network_journal" }, "no page read while subscribed")
        assertEquals(0, app.requests.count { it == "network_body_range" })
        assertEquals(opened, app.feedId)
    }

    @Test
    fun `a batch already in is skipped, a gap ends the subscription, and pages are read again`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(2) { http(app) }
        val host = index(app)
        host.openFeed()
        repeat(2) { http(app) }
        assertEquals(HostIndex.Applied.APPLIED, host.apply(app.batch(2, 4)))
        assertEquals(HostIndex.Applied.DUPLICATE, host.apply(app.batch(2, 4)), "the same batch again")
        repeat(3) { http(app) }
        assertEquals(HostIndex.Applied.GAP, host.apply(app.batch(5, 7)), "the batch from 4 to 5 was lost")
        assertEquals(false, host.feedLive)
        assertEquals(plain(app.deviceAnswer(NetworkQuery(limit = 100))).entries, plain(host.list(NetworkQuery(limit = 100))).entries, "read by pages")
    }

    @Test
    fun `a batch of an earlier subscription is not taken`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        http(app)
        val host = index(app)
        host.openFeed()
        http(app)
        assertEquals(HostIndex.Applied.STALE, host.apply(app.batch(1, 2, subscription = "f0")))
    }

    private fun endFeed(app: FakeJournalApp, host: HostIndex, sent: Int) {
        app.feedId?.let { host.feedEnded(io.yamsergey.dta.protocol.network.FeedEnded(it, "closed", "0:$sent")) }
        app.feedId = null
    }

    @Test
    fun `whatever happens on the way, the archive ends equal to the app's history, nothing doubled`() = runBlocking<Unit> {
        repeat(300) { run -> property(run, seed = run) }
    }

    /** D-031: QA's seed 10069 (harness QaFeedPropertyTest, run 69): a rollback past the record that completed an entry. */
    @Test
    fun `a rollback past an entry's completion leaves the archive with the app's pending record (seed 10069)`() = runBlocking<Unit> {
        property(69, seed = 10_069)
    }

    /** D-031: the completion of the last entry read, cut when read, is applied once whole (its pending record is not "the same record"). */
    @Test
    fun `a completion cut when read is applied once whole, though its entry was the last read`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        http(app)
        val pending = http(app, state = HttpState.PENDING, body = null)
        app.record(pending.copy(state = HttpState.COMPLETE, status = 204))
        app.cutLines += 2 // the completion is being written
        val host = index(app)
        host.list(NetworkQuery())
        app.cutLines.clear()
        val got = host.list(NetworkQuery()).entries.single { it.id == pending.id } as HttpEntry
        assertEquals(HttpState.COMPLETE, got.state)
        assertEquals(1, host.history().archive.installations.single().epochs.size, "not a rollback")
    }

    private suspend fun property(run: Int, seed: Int, trace: Boolean = false) {
        val app = FakeJournalApp().apply { attachBodies = run % 2 == 0 }
        val host = index(app, File(dir, "r$run").apply { mkdirs() })
        val r = Random(seed)
        fun log(what: String) { if (trace) println("D-031 step: $what; app ${app.records.size} lines (${app.records.map { "${it.id.substringAfter(':')}${if ((it as? HttpEntry)?.state == HttpState.PENDING) "p" else "c"}" }}) feed ${app.feedId}") }
        var sent = 0 // the line up to which the app has sent batches of its current subscription
        repeat(r.nextInt(0, 5)) { http(app) }
        log("start")
        repeat(40) {
            when (r.nextInt(12)) {
                0, 1, 2 -> repeat(r.nextInt(1, 4)) {
                    // New traffic, and an entry finishing (a second record of the same id).
                    val pending = http(app, state = HttpState.PENDING, body = null)
                    if (r.nextBoolean()) app.record(pending.copy(state = HttpState.COMPLETE, status = 204))
                    log("traffic")
                }
                3 -> if (host.feedLive || r.nextBoolean()) host.openFeed()?.subscription?.let { sent = app.feedFrom!!.substringAfter(':').toInt(); log("feed opened from $sent") }
                4, 5 -> if (app.feedId != null && sent < app.records.size) {
                    val to = r.nextInt(sent + 1, app.records.size + 1)
                    val applied = host.apply(app.batch(sent, to)); log("batch $sent..$to $applied"); sent = to
                }
                6 -> if (app.feedId != null && sent < app.records.size) { sent = r.nextInt(sent + 1, app.records.size + 1); log("lost up to $sent") } // lost
                7 -> if (app.feedId != null && sent > 0) { val from = r.nextInt(0, sent); val applied = host.apply(app.batch(from, sent)); log("again $from..$sent $applied") } // again
                8 -> { host.feedEnded(io.yamsergey.dta.protocol.network.FeedEnded(app.feedId ?: "f0", "overflow", "0:$sent")); log("overflow") }
                9 -> { host.list(NetworkQuery(limit = 1)); log("query") }
                // A clear ends the subscription (closed); a snapshot taken back restarts the process (the channel ends).
                10 -> if (r.nextInt(4) == 0) { app.reset("j${run}-${r.nextInt(1000)}", "cleared"); endFeed(app, host, sent); sent = 0; log("clear") }
                11 -> if (r.nextInt(4) == 0 && app.records.size > 2) { val to = r.nextInt(1, app.records.size); app.goBack(to); endFeed(app, host, sent); sent = 0; log("rollback to $to lines") }
            }
        }
        // The app sends what it wrote after its last batch, as it does while subscribed.
        if (host.feedLive && app.feedId != null) host.apply(app.batch(minOf(sent, app.records.size)))
        val expected = plain(app.deviceAnswer(NetworkQuery(limit = 10_000)))
        val got = plain(host.list(NetworkQuery(limit = 10_000)))
        assertEquals(expected.entries, got.entries, "run $run (seed $seed): missing ${expected.entries.map { it.id } - got.entries.map { it.id }.toSet()}, extra ${got.entries.map { it.id } - expected.entries.map { it.id }.toSet()}, differ ${expected.entries.zip(got.entries).filter { (a, b) -> a != b }.map { (a, b) -> "${a.id}: app ${(a as? HttpEntry)?.state}/${(a as? HttpEntry)?.status} archive ${(b as? HttpEntry)?.state}/${(b as? HttpEntry)?.status}" }.take(3)}; ${expected.entries.size} vs ${got.entries.size}")
        assertEquals(got.entries.map { it.id }.toSet().size, got.entries.size, "run $run: nothing doubled")
        assertTrue(got.coverage.bodiesNotArchived == null, "run $run")
    }

    @Test
    fun `the history says how far behind the app the archive is, the batch's wait on the device and its wait here`() = runBlocking<Unit> {
        var now = 50_000L
        val app = FakeJournalApp()
        http(app)
        val host = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { app }
        host.openFeed()
        assertEquals(null, host.history().device?.feedLagMs, "nothing came by the feed yet")
        http(app); http(app)
        // Waited 700 ms on the device; here it waits 300 ms for the archive (another batch, a query).
        val received = now
        now += 300
        host.apply(app.batch(1).copy(waitedMs = 700), receivedMs = received)
        http(app)
        host.apply(app.batch(3).copy(waitedMs = 20), receivedMs = now)
        assertEquals(1_000L, host.history().device?.feedLagMs, "the most in the last 10 s")
        now += 11_000
        assertEquals(null, host.history().device?.feedLagMs, "nothing came in the last 10 s")
    }

    /** D-033: opening an entry this computer holds never waits on the app, and never holds the feed. */
    @Test
    fun `an entry already archived opens without asking the app, and the feed is not held meanwhile`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(3) { http(app) }
        val host = index(app)
        assertNotNull(host.openFeed()?.subscription)
        app.identityDelayMs = 3_000
        val asked = app.requests.size
        val started = System.nanoTime()
        val detail = host.entry("r1-1:2")
        val openMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("r1-1:2", detail.entry.id)
        assertTrue(openMs < 500, "opened in $openMs ms")
        assertEquals(emptyList(), app.requests.drop(asked), "the app was not asked")
        // A batch the app writes meanwhile is applied at once.
        repeat(2) { http(app) }
        val (applied, applyMs) = run { val t = System.nanoTime(); host.apply(app.batch(3)) to (System.nanoTime() - t) / 1_000_000 }
        assertEquals(HostIndex.Applied.APPLIED, applied)
        assertTrue(applyMs < 500, "applied in $applyMs ms")
        assertEquals("r1-1:5", host.entry("r1-1:5").entry.id)
    }

    /** D-033, the other side: an entry this computer does not hold yet is still answered (the app is asked). */
    @Test
    fun `an entry not archived yet is still found`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(3) { http(app) }
        val host = index(app)
        assertNotNull(host.openFeed()?.subscription)
        http(app) // written by the app, its batch not here yet
        assertEquals("r1-1:4", host.entry("r1-1:4").entry.id)
    }
}
