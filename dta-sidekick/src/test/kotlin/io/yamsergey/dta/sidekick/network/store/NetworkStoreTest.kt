package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.RunEnd
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkStoreTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun closeStores() = opened.forEach { it.close() }

    private fun store(
        root: File? = dir,
        queueCapacity: Int = 10_000,
        startWriter: Boolean = true,
        pid: Int = 42,
        bounds: StoreBounds = StoreBounds(),
    ) = NetworkStore(
        storage = root?.let { StorageDir(it) },
        pid = pid,
        clock = { now++ },
        queueCapacity = queueCapacity,
        startWriter = startWriter,
        bounds = bounds,
    ).also { opened += it }

    private fun http(store: NetworkStore, url: String = "http://h/x", state: HttpState = HttpState.PENDING): HttpEntry =
        HttpEntry(id = store.nextId(), runId = store.runId, timeMs = now++, library = "okhttp3", method = "GET", url = url, state = state)

    @Test
    fun `no limits by default - neither the history nor a body is bounded`() {
        assertNull(StoreBounds().historyBytes)
        assertNull(StoreBounds().bodyBytes)
    }

    @Test
    fun `ids are run-scoped and monotonic`() {
        val s = store()
        assertEquals("r1000-42", s.runId)
        assertEquals(listOf("r1000-42:1", "r1000-42:2"), listOf(s.nextId(), s.nextId()))
    }

    @Test
    fun `upserts fold to the latest record per id, keeping first-seen order`() {
        val s = store()
        val a = http(s, "http://h/a")
        val b = http(s, "http://h/b")
        s.upsert(a)
        s.upsert(b)
        s.upsert(a.copy(state = HttpState.COMPLETE, status = 200))
        val page = s.list(order = Order.OLDEST_FIRST, limit = 10)
        assertEquals(listOf(a.id, b.id), page.entries.map { it.id })
        assertEquals(HttpState.COMPLETE, (s.get(a.id) as HttpEntry).state)
        assertEquals(2, page.total)
    }

    @Test
    fun `newest first is the default order and the cursor continues a page`() {
        val s = store()
        val ids = (1..5).map { http(s).also(s::upsert).id }
        val first = s.list(limit = 2)
        assertEquals(ids.reversed().take(2), first.entries.map { it.id })
        assertEquals(5, first.total)
        assertEquals(ids[3], first.next)
        val second = s.list(limit = 2, before = first.next)
        assertEquals(listOf(ids[2], ids[1]), second.entries.map { it.id })
        val last = s.list(limit = 2, before = second.next)
        assertEquals(listOf(ids[0]), last.entries.map { it.id })
        assertNull(last.next)
    }

    @Test
    fun `an unknown cursor is invalid params`() {
        val s = store()
        val e = assertFailsWith<InvalidParamsException> { s.list(limit = 2, before = "r9-9:9") }
        assertTrue("r9-9:9" in e.message!!)
    }

    @Test
    fun `predicate narrows the list and total counts matches`() {
        val s = store()
        repeat(4) { i -> s.upsert(http(s, "http://h/${if (i % 2 == 0) "even" else "odd"}")) }
        val page = s.list(limit = 10) { (it as HttpEntry).url.endsWith("even") }
        assertEquals(2, page.total)
        assertEquals(4, page.searched)
    }

    @Test
    fun `body sink stores bytes readable by id and part`() {
        val s = store()
        val e = http(s)
        val sink = s.openBodySink(e.id, BodyPart.RESPONSE)!!
        val bytes = ByteArray(100_000) { it.toByte() }
        sink.write(bytes, 0, 60_000)
        sink.write(bytes, 60_000, 40_000)
        sink.close()
        assertContentEquals(bytes, s.openBody(e.id, BodyPart.RESPONSE)!!.use { it.readBytes() })
        assertNull(s.openBody(e.id, BodyPart.REQUEST))
    }

    @Test
    fun `records are written to the segment log by the writer`() {
        val s = store()
        val e = http(s)
        s.upsert(e)
        s.flush()
        val log = File(dir, "segments").listFiles()!!.sortedBy { it.name }.joinToString("") { it.readText() }
        assertTrue(e.id in log, log)
        assertTrue(s.coverage().persisted)
    }

    @Test
    fun `writer queue overflow is counted as dropped and never blocks`() {
        val s = store(queueCapacity = 3, startWriter = false)
        repeat(5) { s.upsert(http(s)) }
        assertEquals(2, s.coverage().dropped)
        assertEquals(5, s.coverage().stored)
    }

    @Test
    fun `an unusable storage dir degrades to memory, stated in coverage`() {
        val notADir = File(dir, "file").apply { writeText("x") }
        val s = store(root = notADir)
        s.upsert(http(s))
        s.flush()
        val coverage = s.coverage()
        assertFalse(coverage.persisted)
        assertEquals(1, coverage.stored)
        assertNull(s.openBodySink("r1000-42:1", BodyPart.RESPONSE))
    }

    @Test
    fun `no storage at all is memory-only`() {
        val s = store(root = null)
        s.upsert(http(s))
        assertFalse(s.coverage().persisted)
        assertEquals(1, s.list(limit = 5).entries.size)
    }

    @Test
    fun `coverage states stored count, earliest time and the live run`() {
        val s = store()
        val first = http(s)
        s.upsert(first)
        s.upsert(http(s))
        val c = s.coverage()
        assertEquals(2, c.stored)
        assertEquals(first.timeMs, c.earliestMs)
        assertEquals(listOf("r1000-42"), c.runs.map { it.runId })
        assertNull(c.runs.single().ended)
    }

    @Test
    fun `upsert says whether the entry was created or updated`() {
        val s = store()
        val e = http(s)
        assertEquals(EntryChange.CREATED, s.upsert(e))
        assertEquals(EntryChange.UPDATED, s.upsert(e.copy(state = HttpState.COMPLETE)))
    }

    @Test
    fun `clear empties entries, segments and bodies and states when`() {
        val s = store()
        val e = http(s)
        s.upsert(e)
        s.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(byteArrayOf(1), 0, 1); close() }
        s.flush()
        val cleared = s.clear()
        assertEquals(1, cleared)
        assertEquals(0, s.coverage().stored)
        assertTrue(s.coverage().clearedAtMs != null)
        assertNull(s.openBody(e.id, BodyPart.RESPONSE))
        assertTrue(File(dir, "bodies").listFiles()!!.isEmpty())
        s.upsert(http(s))
        s.flush()
        val log = File(dir, "segments").listFiles()!!.joinToString("") { it.readText() }
        assertFalse(e.id in log, "cleared entries are gone from the log")
    }

    // ---- persistence (US5) ----------------------------------------------------------

    private fun restart(s: NetworkStore, pid: Int = 43, bounds: StoreBounds = StoreBounds()): NetworkStore {
        s.close()
        now += 1000
        return store(pid = pid, bounds = bounds)
    }

    @Test
    fun `a new process loads earlier runs, labeled, before its own entries`() {
        val first = store()
        val old = http(first, state = HttpState.COMPLETE)
        first.upsert(old)
        val second = restart(first)
        val fresh = http(second)
        second.upsert(fresh)
        assertEquals(listOf(old.id, fresh.id), second.list(order = Order.OLDEST_FIRST, limit = 10).entries.map { it.id })
        val runs = second.coverage().runs
        assertEquals(listOf(first.runId, second.runId), runs.map { it.runId })
        assertEquals(RunEnd.PROCESS_ENDED, runs[0].ended)
        assertEquals(old.timeMs, runs[0].endedAtMs)
        assertNull(runs[1].ended)
    }

    @Test
    fun `in-flight entries of a finished run become process-ended`() {
        val first = store()
        val pendingHttp = http(first)
        first.upsert(pendingHttp)
        val socket = WsConnectionEntry(first.nextId(), first.runId, now++, "okhttp3-ws", url = "ws://h", state = WsState.OPEN)
        first.upsert(socket)
        val second = restart(first)
        assertEquals(HttpState.PROCESS_ENDED, (second.get(pendingHttp.id) as HttpEntry).state)
        assertEquals(WsState.PROCESS_ENDED, (second.get(socket.id) as WsConnectionEntry).state)
    }

    @Test
    fun `bodies of earlier runs stay readable`() {
        val first = store()
        val e = http(first)
        first.upsert(e)
        first.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(byteArrayOf(7, 8), 0, 2); close() }
        val second = restart(first)
        assertContentEquals(byteArrayOf(7, 8), second.openBody(e.id, BodyPart.RESPONSE)!!.use { it.readBytes() })
    }

    @Test
    fun `over the history bound the oldest entries and their bodies are evicted, stated`() {
        val bounds = StoreBounds(historyBytes = 20_000, segmentBytes = 2_000)
        val s = store(bounds = bounds)
        val ids = (1..20).map { i ->
            val e = http(s, "http://h/$i")
            s.upsert(e)
            s.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(ByteArray(2_000), 0, 2_000); close() }
            s.flush()
            e.id
        }
        s.evictIfNeeded()
        val c = s.coverage()
        assertTrue(c.evicted > 0, "evicted ${c.evicted}")
        assertTrue(s.diskBytes() <= bounds.historyBytes!!, "disk ${s.diskBytes()}")
        val kept = s.list(order = Order.OLDEST_FIRST, limit = 100).entries.map { it.id }
        assertEquals(ids.takeLast(kept.size), kept, "the newest survive, in order")
        assertEquals(s.get(kept.first())!!.timeMs, c.earliestMs)
        assertNull(s.openBody(ids.first(), BodyPart.RESPONSE))
    }

    @Test
    fun `evicted entries stay evicted after a restart, even if updated later`() {
        val bounds = StoreBounds(historyBytes = 6_000, segmentBytes = 1_000)
        val s = store(bounds = bounds)
        val first = http(s, "http://h/first")
        s.upsert(first)
        s.openBodySink(first.id, BodyPart.RESPONSE)!!.apply { write(ByteArray(5_000), 0, 5_000); close() }
        repeat(5) { i -> s.upsert(http(s, "http://h/filler-$i")); s.flush() }
        val big = http(s, "http://h/big")
        s.upsert(big)
        s.openBodySink(big.id, BodyPart.RESPONSE)!!.apply { write(ByteArray(3_000), 0, 3_000); close() }
        s.flush()
        s.evictIfNeeded()
        assertNull(s.get(first.id))
        s.upsert(first.copy(state = HttpState.COMPLETE)) // a late update for an evicted entry
        val evicted = s.coverage().evicted
        val again = restart(s, bounds = bounds)
        assertNull(again.get(first.id), "evicted entry must not come back")
        assertEquals(evicted, again.coverage().evicted)
    }

    @Test
    fun `clear also survives a restart`() {
        val s = store()
        s.upsert(http(s))
        s.flush()
        s.clear()
        val again = restart(s)
        assertEquals(0, again.coverage().stored)
        assertEquals(1, again.coverage().runs.size, "only the new live run is left")
    }

    @Test
    fun `runs scope current is the live run only`() {
        val first = store()
        first.upsert(http(first))
        val second = restart(first)
        second.upsert(http(second))
        assertEquals(1, second.list(limit = 10) { it.runId == second.runId }.total)
    }
}
