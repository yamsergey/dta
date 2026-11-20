package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 068 (research R1, R2): the journal read from a position and kept open. What was written
 * after the position comes first, then every record right after it is written, each once and in
 * the journal's order, whatever the writer does meanwhile.
 */
class NetworkStoreFeedTest {
    @TempDir
    lateinit var dir: File

    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store(segmentBytes: Long = 4L * 1024 * 1024) =
        NetworkStore(StorageDir(dir), pid = 42, bounds = StoreBounds(segmentBytes = segmentBytes)).also { opened += it; assertTrue(it.awaitLoaded()) }

    private fun http(s: NetworkStore, state: HttpState = HttpState.COMPLETE) =
        HttpEntry(s.nextId(), s.runId, System.currentTimeMillis(), "okhttp3", method = "GET", url = "http://h/x", state = state)

    @Test
    fun `opened from a position, it gives what came after it, then each record as it is written`() {
        val s = store()
        repeat(5) { s.upsert(http(s)) }
        s.flush()
        val at = s.journal(null, 3).next
        val live = Collections.synchronizedList(ArrayList<Pair<NetworkEntry, String>>())
        val start = assertNotNull(s.startLive(at, limit = 100) { e, pos, _ -> live += e to pos })
        assertEquals(s.journal(at, 100).records.map { it.id }, start.records.map { it.id }, "the tail after the position")
        assertEquals(s.journal(at, 100).next, start.to)
        repeat(3) { s.upsert(http(s)) }
        s.flush()
        assertEquals(s.journal(start.to, 100).records.map { it.id }, live.map { it.first.id }, "then each record once written")
        assertEquals(s.journal(start.to, 100).next, live.last().second, "with its position")
        s.stopLive()
        s.upsert(http(s)); s.flush()
        assertEquals(3, live.size, "nothing after it stops")
    }

    @Test
    fun `more than a page written after the position is not opened, pages are read first`() {
        val s = store()
        repeat(30) { s.upsert(http(s)) }
        s.flush()
        assertNull(s.startLive("0:0", limit = 10) { _, _, _ -> })
    }

    @Test
    fun `while the writer writes, nothing falls between the tail and the live records, and nothing comes twice`() {
        repeat(60) { run ->
            val sub = File(dir, "r$run").apply { mkdirs() }
            val s = NetworkStore(StorageDir(sub), pid = 42, bounds = StoreBounds(segmentBytes = if (run % 2 == 0) 2_000 else 4L shl 20))
                .also { opened += it; assertTrue(it.awaitLoaded()) }
            val r = Random(run)
            val ids = ArrayList<String>()
            repeat(r.nextInt(0, 40)) { val e = http(s); ids += e.id; s.upsert(e) }
            s.flush()
            val at = s.journal(null, r.nextInt(1, 50)).next
            val writer = Thread {
                repeat(200) { n ->
                    // Updates too: an entry finishing is a second record of the same id.
                    val e = http(s, if (n % 5 == 0) HttpState.PENDING else HttpState.COMPLETE)
                    s.upsert(e)
                    if (n % 5 == 0) s.upsert(e.copy(state = HttpState.COMPLETE))
                    if (r.nextInt(7) == 0) Thread.yield()
                }
            }
            val got = Collections.synchronizedList(ArrayList<String>())
            var positions = ArrayList<String>()
            writer.start()
            Thread.sleep(r.nextLong(0, 3))
            val start = assertNotNull(s.startLive(at, limit = 100_000) { e, pos, _ -> got += e.id; synchronized(positions) { positions += pos } })
            writer.join()
            s.flush()
            s.stopLive()
            val expected = s.journal(at, 100_000).records.map { it.id }
            assertEquals(expected, start.records.map { it.id } + got, "run $run")
            val last = synchronized(positions) { positions.lastOrNull() } ?: start.to
            assertEquals(s.journal(at, 100_000).next, last, "run $run: the last position is the journal's end")
        }
    }
}
