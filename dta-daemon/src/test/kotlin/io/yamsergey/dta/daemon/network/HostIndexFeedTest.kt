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
        repeat(300) { run ->
            val app = FakeJournalApp().apply { attachBodies = run % 2 == 0 }
            val host = index(app, File(dir, "r$run").apply { mkdirs() })
            val r = Random(run)
            var sent = 0 // the line up to which the app has sent batches of its current subscription
            repeat(r.nextInt(0, 5)) { http(app) }
            repeat(40) {
                when (r.nextInt(12)) {
                    0, 1, 2 -> repeat(r.nextInt(1, 4)) {
                        // New traffic, and an entry finishing (a second record of the same id).
                        val pending = http(app, state = HttpState.PENDING, body = null)
                        if (r.nextBoolean()) app.record(pending.copy(state = HttpState.COMPLETE, status = 204))
                    }
                    3 -> if (host.feedLive || r.nextBoolean()) host.openFeed()?.subscription?.let { sent = app.feedFrom!!.substringAfter(':').toInt() }
                    4, 5 -> if (app.feedId != null && sent < app.records.size) {
                        val to = r.nextInt(sent + 1, app.records.size + 1)
                        host.apply(app.batch(sent, to)); sent = to
                    }
                    6 -> if (app.feedId != null && sent < app.records.size) { sent = r.nextInt(sent + 1, app.records.size + 1) } // lost
                    7 -> if (app.feedId != null && sent > 0) host.apply(app.batch(r.nextInt(0, sent), sent)) // again
                    8 -> host.feedEnded(io.yamsergey.dta.protocol.network.FeedEnded(app.feedId ?: "f0", "overflow", "0:$sent"))
                    9 -> host.list(NetworkQuery(limit = 1))
                    // A clear ends the subscription (closed); a snapshot taken back restarts the process (the channel ends).
                    10 -> if (r.nextInt(4) == 0) { app.reset("j${run}-${r.nextInt(1000)}", "cleared"); endFeed(app, host, sent); sent = 0 }
                    11 -> if (r.nextInt(4) == 0 && app.records.size > 2) { app.goBack(r.nextInt(1, app.records.size)); endFeed(app, host, sent); sent = 0 }
                }
            }
            // The app sends what it wrote after its last batch, as it does while subscribed.
            if (host.feedLive && app.feedId != null) host.apply(app.batch(minOf(sent, app.records.size)))
            val expected = plain(app.deviceAnswer(NetworkQuery(limit = 10_000)))
            val got = plain(host.list(NetworkQuery(limit = 10_000)))
            assertEquals(expected.entries, got.entries, "run $run: missing ${expected.entries.map { it.id } - got.entries.map { it.id }.toSet()}, extra ${got.entries.map { it.id } - expected.entries.map { it.id }.toSet()}, differ ${expected.entries.zip(got.entries).filter { (a, b) -> a != b }.map { it.first.id + '/' + it.second.id }.take(3)}; ${expected.entries.size} vs ${got.entries.size}")
            assertEquals(got.entries.map { it.id }.toSet().size, got.entries.size, "run $run: nothing doubled")
            assertTrue(got.coverage.bodiesNotArchived == null, "run $run")
        }
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
}
