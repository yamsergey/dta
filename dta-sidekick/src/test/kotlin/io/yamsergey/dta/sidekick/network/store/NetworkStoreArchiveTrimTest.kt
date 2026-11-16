package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.ArchiveAck
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.HistoryDefaults
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 066: the device keeps only what this computer has not archived (research R1, R2, R4). */
class NetworkStoreArchiveTrimTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store(pid: Int = 42, segmentBytes: Long = 4L * 1024 * 1024) =
        NetworkStore(StorageDir(dir), pid = pid, clock = { now++ }, bounds = StoreBounds(segmentBytes = segmentBytes))
            .also { opened += it; assertTrue(it.awaitLoaded()) }

    private fun http(s: NetworkStore, state: HttpState = HttpState.COMPLETE) =
        HttpEntry(id = s.nextId(), runId = s.runId, timeMs = now++, library = "okhttp3", method = "GET", url = "http://h/x", state = state)

    // ---- A2: settings ------------------------------------------------------------------

    @Test
    fun `the budget and warning size default to 256 and 512 MB`() {
        val s = store()
        assertEquals(HistoryDefaults.KEEP_BYTES, s.settings().keepBytes)
        assertEquals(HistoryDefaults.WARN_BYTES, s.settings().warnBytes)
    }

    @Test
    fun `settings are kept across restarts and a clear`() {
        val s = store()
        s.setSettings(keepBytes = 32L shl 20, warnBytes = 64L shl 20)
        s.close(); opened.clear()
        val again = store()
        assertEquals(32L shl 20, again.settings().keepBytes)
        assertEquals(64L shl 20, again.settings().warnBytes)
        again.upsert(http(again))
        again.clear()
        assertEquals(32L shl 20, again.settings().keepBytes)
        assertEquals(64L shl 20, again.coverage().warnBytes)
    }

    @Test
    fun `one value can be set alone, and refused values say why`() {
        val s = store()
        assertEquals(HistoryDefaults.WARN_BYTES, s.setSettings(keepBytes = 100L shl 20).warnBytes)
        assertEquals(100L shl 20, s.settings().keepBytes)
        val small = assertFailsWith<InvalidParamsException> { s.setSettings(keepBytes = 1024) }
        assertTrue(small.message!!.contains("16 MB"), small.message)
        assertFailsWith<InvalidParamsException> { s.setSettings(warnBytes = 50L shl 20) }
        assertEquals(100L shl 20, s.settings().keepBytes, "a refused change changes nothing")
    }

    // ---- A3: the segment index -----------------------------------------------------------

    /** One record per segment (any record overflows a 1-byte segment), so segment k holds record k. */
    private fun tiny() = store(segmentBytes = 1)

    @Test
    fun `the store knows which ids each segment holds, after writes and after a restart`() {
        val s = tiny()
        val a = http(s, HttpState.PENDING).also(s::upsert)
        val b = http(s).also(s::upsert)
        s.upsert(a.copy(state = HttpState.COMPLETE))
        s.flush()
        val expected = mapOf("seg-000001.jsonl" to listOf(a.id), "seg-000002.jsonl" to listOf(b.id), "seg-000003.jsonl" to listOf(a.id))
        assertEquals(expected, s.segmentIdsForTest())
        s.close(); opened.clear()
        val again = tiny()
        assertEquals(expected, again.segmentIdsForTest().filterKeys { it in expected })
    }

    @Test
    fun `the index costs about 8 bytes per record`() {
        val s = store(segmentBytes = 16 * 1024)
        repeat(50_000) { i -> s.upsert(http(s)); if (i % 2_000 == 0) s.flush() }
        s.flush()
        val records = s.segmentIdsForTest().values.sumOf { it.size }
        assertEquals(50_000, records, "every record is indexed")
        val perRecord = s.segmentIndexBytesForTest().toDouble() / records
        println("066 segment index: %.1f bytes per record".format(perRecord))
        assertTrue(perRecord <= 12.0, "index too large: $perRecord bytes per record")
    }

    // ---- A4: the acknowledgement and the trim pass ------------------------------------------

    private fun withBody(s: NetworkStore, bytes: Int = 1000, state: HttpState = HttpState.COMPLETE): HttpEntry {
        val e = http(s, state).also(s::upsert)
        s.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(ByteArray(bytes), 0, bytes); close() }
        return e
    }

    private fun ack(s: NetworkStore, through: String, keep: List<String> = emptyList(), host: String = "h1") =
        s.acceptAck(ArchiveAck(s.identity().journalId, host, through, keep))

    @Test
    fun `without an acknowledgement nothing leaves the device, and the answer says why`() {
        val s = tiny()
        s.setBudgetForTest(2_000)
        List(10) { withBody(s) }
        s.trimNow()
        assertEquals(10, s.coverage().stored)
        assertEquals("no-archive", s.coverage().trimHeld)
    }

    @Test
    fun `archived entries leave oldest first, with their bodies, until the history fits`() {
        val s = tiny()
        val ids = List(10) { withBody(s).id }
        s.flush()
        s.setBudgetForTest(s.diskBytes() - 5_000)
        assertTrue(ack(s, through = "8:0").accepted)
        s.trimNow()
        assertTrue(s.diskBytes() <= s.settings().keepBytes, "fits: ${s.diskBytes()} > ${s.settings().keepBytes}")
        val gone = ids.filter { s.bodySize(it, BodyPart.RESPONSE) == null }
        assertEquals(ids.take(gone.size), gone, "the oldest went first")
        assertTrue(gone.size in 4..7, "removed ${gone.size}")
        assertEquals(10 - gone.size, s.coverage().stored)
        assertEquals(mapOf("h1" to gone.size), s.coverage().archivedOnly)
        assertNull(s.coverage().trimHeld)
        gone.forEach { assertNull(s.get(it), it) }
        ids.drop(gone.size).forEach { assertNotNull(s.get(it), it) }
    }

    @Test
    fun `nothing at or after the acknowledged segment is removed`() {
        val s = tiny()
        val ids = List(10) { withBody(s).id }
        s.flush()
        s.setBudgetForTest(1_000)
        ack(s, through = "4:1")
        s.trimNow()
        assertEquals(ids.take(3), ids.filter { s.get(it) == null }, "only segments 1–3, before the host's cursor segment")
        assertEquals("unarchived", s.coverage().trimHeld)
    }

    @Test
    fun `entries in flight or still being copied are carried forward, not removed`() {
        val s = tiny()
        val pending = withBody(s, state = HttpState.PENDING)
        val ws = WsConnectionEntry(s.nextId(), s.runId, now++, "okhttp3", null, "ws://h", WsState.OPEN).also(s::upsert)
        val copying = withBody(s)
        val done = withBody(s)
        List(4) { withBody(s) }
        s.flush()
        s.setBudgetForTest(1_000)
        ack(s, through = "6:0", keep = listOf(copying.id))
        s.trimNow()
        assertNull(s.get(done.id))
        listOf(pending, ws, copying).forEach { e ->
            assertNotNull(s.get(e.id), e.id)
            val latest = s.segmentIdsForTest().filterValues { e.id in it }.keys.maxOrNull()!!
            assertTrue(latest > "seg-000008.jsonl", "${e.id} was re-appended: $latest")
        }
        assertNotNull(s.bodySize(pending.id, BodyPart.RESPONSE))
        assertNotNull(s.bodySize(copying.id, BodyPart.RESPONSE))
        val journal = s.journal(after = null, limit = 1000).records.map { it.id }
        assertTrue(journal.containsAll(listOf(pending.id, ws.id, copying.id)))
        assertFalse(done.id in journal)
    }

    @Test
    fun `an entry updated later keeps its bodies when its old record goes`() {
        val s = tiny()
        val e = withBody(s)
        List(3) { withBody(s) }
        s.upsert(e.copy(status = 404))
        s.flush()
        s.setBudgetForTest(1_000)
        ack(s, through = "4:0")
        s.trimNow()
        assertEquals(404, (s.get(e.id) as HttpEntry).status)
        assertNotNull(s.bodySize(e.id, BodyPart.RESPONSE))
    }

    @Test
    fun `stale, unreadable and earlier acknowledgements`() {
        val s = tiny()
        List(6) { withBody(s) }
        s.flush()
        assertEquals("journal-changed", s.acceptAck(ArchiveAck("other", "h1", "3:0")).reason)
        assertEquals("invalid-cursor", s.acceptAck(ArchiveAck(s.identity().journalId, "h1", "soon")).reason)
        ack(s, through = "5:0")
        ack(s, through = "2:0", keep = listOf("x"))
        s.setBudgetForTest(1_000)
        s.trimNow()
        assertEquals(2, s.coverage().stored, "a later acknowledgement behind the first does not move the trim point back")
    }

    @Test
    fun `an acknowledgement while the earlier runs are read is refused for later`() {
        tiny().apply { List(3) { withBody(this) }; flush(); close() }
        opened.clear()
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val s = NetworkStore(StorageDir(dir), pid = 43, clock = { now++ }, bounds = StoreBounds(segmentBytes = 1), beforeLoad = { reading.countDown(); release.await() })
            .also { opened += it }
        reading.await()
        assertEquals("reading", s.acceptAck(ArchiveAck(s.identity().journalId, "h1", "2:0")).reason)
        release.countDown()
    }

    @Test
    fun `the acknowledgement survives a restart, and trimming goes on with no computer`() {
        val s = tiny()
        List(6) { withBody(s) }
        s.flush()
        ack(s, through = "5:0")
        s.close(); opened.clear()
        val again = tiny()
        again.setBudgetForTest(1_000)
        again.trimNow()
        assertEquals(2, again.coverage().stored)
        assertNotNull(again.coverage().lastArchivedMs)
    }

    @Test
    fun `a lower budget trims more, and a clear forgets the acknowledgement`() {
        val s = tiny()
        List(8) { withBody(s) }
        s.flush()
        ack(s, through = "8:0")
        s.setBudgetForTest(s.diskBytes() - 1_500)
        s.trimNow()
        val first = s.coverage().stored
        s.setBudgetForTest(1_000)
        s.trimNow()
        assertTrue(s.coverage().stored < first)
        s.clear()
        assertNull(s.coverage().lastArchivedMs)
        assertNull(s.coverage().archivedOnly)
    }

    @Test
    fun `the journal still pages what is left, in order, after a trim`() {
        val s = tiny()
        val ids = List(8) { withBody(s).id }
        s.flush()
        ack(s, through = "5:0")
        s.setBudgetForTest(1_000)
        s.trimNow()
        assertEquals(ids.drop(4), s.journal(after = null, limit = 100).records.map { it.id })
        assertEquals(ids.drop(5), s.journal(after = "5:1", limit = 100).records.map { it.id }, "the host's cursor still resumes")
    }

    // ---- C1: the start warning ------------------------------------------------------------

    @Test
    fun `a history over its warning size is said once in the device log at the start`() {
        tiny().apply {
            List(5) { withBody(this, bytes = 20_000) }
            flush()
            close()
        }
        opened.clear()
        // Settings below 16 MB are for tests only: write them through a store, then start again.
        NetworkStore(StorageDir(dir), pid = 9, clock = { now++ }).also { it.awaitLoaded(); it.setWarnForTest(50_000); it.close() }
        val said = mutableListOf<String>()
        NetworkStore(StorageDir(dir), pid = 10, clock = { now++ }, warn = { said += it }).also { opened += it; it.awaitLoaded() }
        Thread.sleep(100)
        val line = said.single()
        assertTrue(line.contains("over its 0 MB warning size") && line.contains("no DTA client has archived it yet") && line.contains("dta network clear"), line)
        val quiet = mutableListOf<String>()
        NetworkStore(StorageDir(File(dir, "other")), pid = 11, clock = { now++ }, warn = { quiet += it }).also { opened += it; it.awaitLoaded() }
        Thread.sleep(100)
        assertTrue(quiet.isEmpty())
    }
}
