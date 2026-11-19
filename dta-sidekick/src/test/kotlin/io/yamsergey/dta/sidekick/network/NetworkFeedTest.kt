package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.FeedBatch
import io.yamsergey.dta.protocol.network.FeedEnded
import io.yamsergey.dta.protocol.network.FeedOpenParams
import io.yamsergey.dta.protocol.network.FeedOpenResult
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import io.yamsergey.dta.sidekick.network.store.StoreBounds
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 068 (contracts/feed.md): the app's side of the feed. */
class NetworkFeedTest {
    @TempDir
    lateinit var dir: File

    private val opened = mutableListOf<NetworkStore>()
    private val feeds = mutableListOf<NetworkFeed>()

    @AfterEach
    fun close() {
        feeds.forEach { it.close() }
        opened.forEach { it.close() }
    }

    private fun store(root: File = dir) = NetworkStore(StorageDir(root), pid = 42, bounds = StoreBounds()).also { opened += it; assertTrue(it.awaitLoaded()) }

    private fun http(s: NetworkStore) = HttpEntry(s.nextId(), s.runId, System.currentTimeMillis(), "okhttp3", method = "GET", url = "http://h/x", state = HttpState.COMPLETE)

    /** What the daemon would receive, decoded. */
    private class Sink {
        val batches = CopyOnWriteArrayList<FeedBatch>()
        val ended = CopyOnWriteArrayList<FeedEnded>()
        @Volatile var gate: CountDownLatch? = null
        fun send(name: String, data: JsonElement?) {
            gate?.await(10, TimeUnit.SECONDS)
            when (name) {
                FeedBatch.EVENT -> batches += DtaJson.decodeFromJsonElement(FeedBatch.serializer(), data!!)
                FeedEnded.EVENT -> ended += DtaJson.decodeFromJsonElement(FeedEnded.serializer(), data!!)
            }
        }
    }

    private fun feed(s: NetworkStore, sink: Sink, maxQueued: Int = 20_000) = NetworkFeed(s, sink::send, maxQueued = maxQueued).also { feeds += it }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) { assertTrue(System.currentTimeMillis() < until, "timed out: $what"); Thread.sleep(5) }
    }

    @Test
    fun `opened from the end of what was read, it sends what came after, then live batches that follow each other`() {
        val s = store()
        repeat(5) { s.upsert(http(s)) }
        s.flush()
        val read = s.journal(null, 3)
        val sink = Sink()
        val f = feed(s, sink)
        val opened = f.open(FeedOpenParams(read.journalId, read.next))
        val id = assertNotNull(opened.subscription, opened.refused)
        repeat(1200) { s.upsert(http(s)) }
        s.flush()
        val all = s.journal(read.next, 100_000)
        waitFor("every record") { sink.batches.sumOf { it.records.size } == all.records.size }
        assertEquals(all.records.map { it.id }, sink.batches.flatMap { it.records }.map { it.id })
        assertEquals(read.next, sink.batches.first().from)
        sink.batches.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, "a batch starts where the previous ended") }
        assertEquals(all.next, sink.batches.last().to)
        assertTrue(sink.batches.all { it.subscription == id && it.journalId == read.journalId && it.records.size <= NetworkFeed.BATCH })
    }

    @Test
    fun `another journal, more than a page behind, or a memory-only history are refused with the reason`() {
        val s = store()
        val sink = Sink()
        val f = feed(s, sink)
        assertEquals(FeedOpenResult.JOURNAL_CHANGED, f.open(FeedOpenParams("other", "0:0")).refused)
        repeat(NetworkFeed.PAGE + 5) { s.upsert(http(s)) }
        s.flush()
        assertEquals(FeedOpenResult.BEHIND, f.open(FeedOpenParams(s.identity().journalId, "0:0")).refused)
        val memory = NetworkStore(null, pid = 1).also { opened += it }
        assertEquals(FeedOpenResult.NOT_PERSISTED, feed(memory, sink).open(FeedOpenParams(memory.identity().journalId, "0:0")).refused)
    }

    @Test
    fun `a subscriber that cannot keep up is ended at a position, and the app is never held`() {
        val s = store()
        val sink = Sink().apply { gate = CountDownLatch(1) }
        val f = feed(s, sink, maxQueued = 100)
        val from = s.journal(null, 1).next
        f.open(FeedOpenParams(s.identity().journalId, from))
        val started = System.currentTimeMillis()
        repeat(2_000) { s.upsert(http(s)) }
        s.flush()
        assertTrue(System.currentTimeMillis() - started < 5_000, "the writer did not wait for the subscriber")
        sink.gate!!.countDown()
        waitFor("the end") { sink.ended.isNotEmpty() }
        val end = sink.ended.single()
        assertEquals(FeedEnded.OVERFLOW, end.reason)
        assertEquals(sink.batches.last().to, end.at, "nothing after the last batch sent")
        val sent = sink.batches.flatMap { it.records }.map { it.id }
        assertEquals(s.journal(from, 100_000).records.map { it.id }.take(sent.size), sent, "what was sent is in order")
    }

    @Test
    fun `a clear ends the subscription, and close stops it`() {
        val s = store()
        val sink = Sink()
        val f = feed(s, sink)
        val id = f.open(FeedOpenParams(s.identity().journalId, s.journal(null, 1).next)).subscription!!
        s.clear()
        waitFor("the end") { sink.ended.isNotEmpty() }
        assertEquals(FeedEnded.CLOSED, sink.ended.single().reason)
        val again = f.open(FeedOpenParams(s.identity().journalId, s.journal(null, 1).next)).subscription!!
        f.close(again)
        s.upsert(http(s)); s.flush()
        Thread.sleep(150)
        assertTrue(sink.batches.none { it.subscription == again && it.records.isNotEmpty() }, "nothing after close")
        assertNull(f.current(), id)
    }

    @Test
    fun `live batches carry the small bodies of their records`() {
        val s = store()
        val sink = Sink()
        val f = feed(s, sink)
        f.open(FeedOpenParams(s.identity().journalId, s.journal(null, 1).next))
        val e = http(s)
        s.openBodySink(e.id, io.yamsergey.dta.protocol.network.BodyPart.RESPONSE)!!.apply { val b = "[1,2,3]".toByteArray(); write(b, 0, b.size); close() }
        s.upsert(e.copy(responseBody = io.yamsergey.dta.protocol.network.BodyInfo(io.yamsergey.dta.protocol.network.BodyPart.RESPONSE, 7,
            completeness = io.yamsergey.dta.protocol.network.Completeness.COMPLETE)))
        s.flush()
        waitFor("the body") { sink.batches.any { b -> b.bodies.orEmpty().any { it.entryId == e.id } } }
        assertEquals("[1,2,3]", sink.batches.flatMap { it.bodies.orEmpty() }.single { it.entryId == e.id }.text)
    }
}
