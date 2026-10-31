package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 011 T006 (US1): the store keeps little in memory and serves its journal to the host. */
class NetworkStoreJournalTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store(pid: Int = 42, window: Int = 3, root: File? = dir) =
        NetworkStore(root?.let { StorageDir(it) }, pid = pid, clock = { now++ }, recentWindow = window).also { opened += it }

    private fun http(s: NetworkStore, state: HttpState = HttpState.COMPLETE, id: String = s.nextId()) =
        HttpEntry(id = id, runId = s.runId, timeMs = now++, library = "okhttp3", method = "GET", url = "http://h/$id", state = state)

    @Test
    fun `finished entries beyond the recent window leave memory, and are still found`() {
        val s = store(window = 3)
        val ids = List(10) { http(s).also(s::upsert).id }
        assertEquals(3, s.inMemory(), "only the recent window is held")
        val old = assertNotNull(s.get(ids.first()), "an older entry is read back from the journal")
        assertEquals(ids.first(), old.id)
        assertEquals(10, s.coverage().stored)
    }

    @Test
    fun `in-flight entries stay in memory however many finish after them`() {
        val s = store(window = 2)
        val pending = http(s, HttpState.PENDING).also(s::upsert)
        val ws = WsConnectionEntry(s.nextId(), s.runId, now++, "okhttp3", null, "ws://h", WsState.OPEN).also(s::upsert)
        repeat(20) { s.upsert(http(s)) }
        assertEquals(4, s.inMemory(), "two in flight plus the window of two")
        assertEquals(pending, s.get(pending.id))
        assertEquals(ws, s.get(ws.id))
    }

    @Test
    fun `a late update of an old entry is not lost`() {
        val s = store(window = 1)
        val first = http(s).also(s::upsert)
        repeat(5) { s.upsert(http(s)) }
        val updated = (s.get(first.id) as HttpEntry).copy(status = 404)
        s.upsert(updated)
        assertEquals(404, (s.get(first.id) as HttpEntry).status)
        assertEquals(6, s.coverage().stored, "an update is not a new entry")
        assertEquals(404, (s.snapshot().first { it.id == first.id } as HttpEntry).status)
    }

    @Test
    fun `the journal pages every record in order, across segments, and says where it ended`() {
        val s = NetworkStore(StorageDir(dir), pid = 42, clock = { now++ }, bounds = StoreBounds(segmentBytes = 600)).also { opened += it }
        val ids = List(12) { http(s).also(s::upsert).id }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = s.journal(after = cursor, limit = 5)
            seen += page.records.map { it.id }
            cursor = page.next
        } while (!page.end)
        assertEquals(ids, seen)
        assertTrue(dir.resolve("segments").listFiles()!!.size > 1, "several segments were read")
        assertTrue(s.journal(after = cursor, limit = 5).records.isEmpty(), "nothing after the end")
        val more = http(s).also(s::upsert)
        assertEquals(listOf(more.id), s.journal(after = cursor, limit = 5).records.map { it.id }, "the cursor resumes where it stopped")
    }

    @Test
    fun `the journal identity is stable, and changes on clear and on new storage`() {
        val s = store()
        val first = s.identity()
        assertEquals("storage-new", first.reason)
        assertEquals(first.journalId, s.identity().journalId)
        s.close(); opened.clear()
        val again = store()
        assertEquals(first.journalId, again.identity().journalId, "a restart continues the journal")
        assertNull(again.identity().reason)
        again.upsert(http(again))
        again.clear()
        val cleared = again.identity()
        assertNotEquals(first.journalId, cleared.journalId)
        assertEquals("cleared", cleared.reason)
        again.close(); opened.clear()
        dir.deleteRecursively()
        val wiped = store()
        assertEquals("storage-new", wiped.identity().reason)
        assertNotEquals(cleared.journalId, wiped.identity().journalId)
    }

    @Test
    fun `a body is served by range`() {
        val s = store()
        val e = http(s).also(s::upsert)
        val bytes = ByteArray(1000) { it.toByte() }
        s.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(bytes, 0, bytes.size); close() }
        assertContentEquals(bytes.copyOfRange(100, 350), s.openBodyRange(e.id, BodyPart.RESPONSE, 100, 250)!!.readBytes())
        assertContentEquals(bytes.copyOfRange(900, 1000), s.openBodyRange(e.id, BodyPart.RESPONSE, 900, 500)!!.readBytes(), "a range past the end is cut")
        assertEquals(1000L, s.bodySize(e.id, BodyPart.RESPONSE))
    }

    @Test
    fun `in-flight entries of an ended process are written as ended, so the journal says so`() {
        val s = store(pid = 1)
        val pending = http(s, HttpState.PENDING).also(s::upsert)
        s.close(); opened.clear()
        val next = store(pid = 2)
        val records = next.journal(after = null, limit = 100).records.filter { it.id == pending.id }
        assertEquals(HttpState.PROCESS_ENDED, (records.last() as HttpEntry).state, "the last record of the entry is its end")
    }

    @Test
    fun `without storage everything stays in memory, as before`() {
        val s = store(window = 2, root = null)
        repeat(10) { s.upsert(http(s)) }
        assertEquals(10, s.inMemory())
        assertEquals(10, s.snapshot().size)
    }
}
