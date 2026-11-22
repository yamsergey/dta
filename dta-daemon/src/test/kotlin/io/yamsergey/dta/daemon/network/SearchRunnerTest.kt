package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.FindEnd
import io.yamsergey.dta.protocol.network.FoundItem
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.SearchStoppedException
import io.yamsergey.dta.protocol.network.find
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 071 B1 (FR-006, FR-008, FR-009, FR-010; SC-002, SC-003, SC-006): the search pool and the stream. */
class SearchRunnerTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()
    private val runner = SearchRunner()

    @AfterEach
    fun close() { stores.forEach { it.close() }; runner.close() }

    private fun item(n: Int) = FoundItem(HttpEntry("r:$n", "r", n.toLong(), "okhttp3", method = "GET", url = "https://api.example.test/$n", state = HttpState.COMPLETE))

    /** A walk of [n] rows; each waits [eachMs]; its stop is checked as the view checks it. */
    private fun walkOf(n: Int, made: AtomicInteger = AtomicInteger(), eachMs: Long = 0): (io.yamsergey.dta.protocol.network.PatternStop, (FoundItem) -> Unit) -> FindEnd = { stop, onItem ->
        for (i in 1..n) {
            if (stop.stopped()) throw SearchStoppedException()
            if (eachMs > 0) Thread.sleep(eachMs)
            made.incrementAndGet()
            onItem(item(i))
        }
        FindEnd(null, null)
    }

    /** A walk stuck in one runaway match until stopped. */
    private val runaway: (io.yamsergey.dta.protocol.network.PatternStop, (FoundItem) -> Unit) -> FindEnd = { stop, _ ->
        io.yamsergey.dta.protocol.network.BodyPattern.find(java.util.regex.Pattern.compile("(x+x+)+y"), "x".repeat(1 shl 20)) { if (stop.stopped()) throw SearchStoppedException() }
        FindEnd(null, null)
    }

    @Test
    fun `rows come before the walk ends, all of them, in order`() = runBlocking<Unit> {
        val got = mutableListOf<FoundItem>()
        val firstAt = CompletableDeferred<Long>()
        val started = System.nanoTime()
        val end = FindPipeline.run(runner, Credit(Int.MAX_VALUE), {}, walkOf(200, eachMs = 2)) { batch ->
            firstAt.complete(System.nanoTime())
            got += batch
        }
        val total = System.nanoTime() - started
        assertEquals((1..200).map { "r:$it" }, got.map { it.entry.id })
        assertTrue(firstAt.await() - started < total / 2, "the first rows came while it walked")
        assertEquals(null, end.next)
    }

    @Test
    fun `a viewer without credit holds the walk, and memory stays bounded`() = runBlocking<Unit> {
        val made = AtomicInteger()
        val credit = Credit(10)
        val got = AtomicInteger()
        val job = async { FindPipeline.run(runner, credit, {}, walkOf(100_000, made)) { got.addAndGet(it.size) } }
        delay(300)
        assertEquals(10, got.get())
        assertTrue(made.get() <= 10 + FindPipeline.BUFFER + FindPipeline.BATCH + 2, "walked ${made.get()} ahead of a viewer that took 10")
        credit.more(1_000)
        withTimeout(5_000) { while (got.get() < 1_010) delay(5) }
        delay(100)
        assertEquals(1_010, got.get())
        job.cancelAndJoin()
    }

    @Test
    fun `a stop ends a runaway match within 200 ms and nothing comes after it`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val job = launch { FindPipeline.run(runner, Credit(100), {}, runaway) { sent.incrementAndGet() } }
        delay(300)
        val at = System.nanoTime()
        job.cancelAndJoin()
        val tookMs = (System.nanoTime() - at) / 1_000_000
        assertTrue(tookMs <= 200, "stopped after $tookMs ms")
        assertEquals(0, sent.get())
        // The place it held is free again: the next search starts at once.
        val waited = AtomicInteger()
        FindPipeline.run(runner, Credit(10), { waited.incrementAndGet() }, walkOf(3)) {}
        assertEquals(0, waited.get())
    }

    @Test
    fun `searches beyond the limit wait in order and say so, and none is dropped`() = runBlocking<Unit> {
        val first = launch { FindPipeline.run(runner, Credit(1), {}, runaway) {} }
        val second = launch { FindPipeline.run(runner, Credit(1), {}, runaway) {} }
        delay(200)
        val positions = mutableListOf<Int>()
        val third = async { FindPipeline.run(runner, Credit(100), { positions += it }, walkOf(5)) {}; "done" }
        val fourth = async { FindPipeline.run(runner, Credit(100), { positions += it }, walkOf(5)) {}; "done" }
        delay(200)
        assertEquals(listOf(1, 2), positions)
        assertEquals(2, runner.waiting)
        first.cancelAndJoin()
        assertEquals("done", withTimeout(2_000) { third.await() })
        second.cancelAndJoin()
        assertEquals("done", withTimeout(2_000) { fourth.await() })
    }

    // --- over a real history

    private fun http(n: Int, body: String) = HttpEntry("r1:$n", "r1", n.toLong(), "okhttp3", method = "GET", url = "https://api.example.test/$n", state = HttpState.COMPLETE, status = 200,
        responseBody = BodyInfo(BodyPart.RESPONSE, body.length.toLong(), completeness = Completeness.COMPLETE, contentType = "text/plain", preview = body.take(BodyInfo.PREVIEW_CHARS)))

    private suspend fun host(bodies: List<String>): HostIndex {
        val app = FakeJournalApp()
        app.runId = "r1"
        bodies.forEachIndexed { i, b -> val e = http(i + 1, b); app.record(e); app.bodies[e.id to BodyPart.RESPONSE] = b.toByteArray() }
        val store = HostStore(dir).also { stores += it }
        return HostIndex(store, DeviceIdentity("dev1"), "io.example") { app }.also { it.list(NetworkQuery()) }
    }

    private suspend fun found(host: HostIndex, q: NetworkQuery, limit: Int? = null): Pair<List<FoundItem>, FindEnd> {
        val got = mutableListOf<FoundItem>()
        val pass = host.find(q, limit)
        val end = FindPipeline.run(runner, Credit(Int.MAX_VALUE), {}, pass.walk) { got += it }
        return got to end
    }

    @Test
    fun `a slow pattern over large bodies finds every match, says nothing about time, and its first row comes early`() = runBlocking<Unit> {
        // 12 bodies of 16 KB; every 3rd holds the match at its end. [ab]*zz backtracks from every start:
        // well over 50 ms a body (the 053 budget skipped them all).
        val bodies = (1..12).map { n -> "ab".repeat(1 shl 13).let { if (n % 3 == 0) it + "zz-hit-$n" else it } }
        val h = host(bodies)
        val q = NetworkQuery(q = "body:/[ab]*zz-hit-\\d+/")
        val expected = (1..12).filter { it % 3 == 0 }.reversed().map { "r1:$it" }
        val started = System.nanoTime()
        val (items, end) = found(h, q)
        println("slow pattern over 12 x 16 KB: ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(expected, items.map { it.entry.id })
        assertTrue(end.notSearched.orEmpty().none { "slow" in it || "skipped" in it }, end.notSearched.toString())
        assertTrue(items.all { it.hits!!.single().term.startsWith("/") })
    }

    @Test
    fun `streamed rows equal one uninterrupted list of the same scope, for any credit and limit`() = runBlocking<Unit> {
        val rnd = Random(71)
        val words = listOf("alpha", "beta", "gamma", "token", "item-7", "ok")
        val bodies = (1..300).map { (1..rnd.nextInt(1, 5)).joinToString(" ") { words.random(rnd) } }
        val h = host(bodies)
        val queries = listOf("token", "alpha beta", "body:/item-\\d/", "\"gamma ok\"", "status:200 token", "-body:alpha token", "beta")
        repeat(1_000) { run ->
            val line = queries[run % queries.size]
            val order = if (rnd.nextBoolean()) Order.NEWEST_FIRST else Order.OLDEST_FIRST
            val q = NetworkQuery(q = line, order = order, limit = NetworkQuery.MAX_LIMIT)
            val listed = h.list(q)
            val credit = Credit(rnd.nextInt(1, 50))
            val got = mutableListOf<FoundItem>()
            val pass = h.find(q, null)
            val walking = async { FindPipeline.run(runner, credit, {}, pass.walk) { got += it } }
            while (!walking.isCompleted) { credit.more(rnd.nextInt(1, 40)); kotlinx.coroutines.yield() }
            walking.await()
            assertEquals(listed.entries.map { it.id }, got.map { it.entry.id }, "run $run: $line $order")
            assertEquals(listed.hits?.let { hits -> listed.entries.map { hits[it.id] } }, listed.hits?.let { got.map { it.hits } }, "hits, run $run")
        }
    }

    @Test
    fun `a limit ends the walk with a cursor that continues it`() = runBlocking<Unit> {
        val h = host((1..30).map { "token $it" })
        val (first, end) = found(h, NetworkQuery(q = "token"), limit = 10)
        assertEquals((30 downTo 21).map { "r1:$it" }, first.map { it.entry.id })
        assertEquals("r1:21", end.next)
        val (rest, end2) = found(h, NetworkQuery(q = "token", before = end.next), limit = 100)
        assertEquals((20 downTo 1).map { "r1:$it" }, rest.map { it.entry.id })
        assertEquals(null, end2.next)
    }
}
