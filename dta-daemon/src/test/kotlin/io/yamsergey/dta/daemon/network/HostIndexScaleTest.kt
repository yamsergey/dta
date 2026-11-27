package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 069 US1/US4: an archive's size does not set how much an answer reads or how much the daemon holds. 100,000 entries
 * here (the million is the benchmark's, D1). D-128: no milliseconds and no heap deltas here (a loaded machine fails them); what
 * is counted is the work: the rows an answer reads from the store, and what the index holds in memory. The timing of the
 * same answers is HostIndexScalePerf, run by qa-perf.
 */
class HostIndexScaleTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    /** The rows the store gave to answers since [mark]: an answer over a large archive reads about one page, not the archive. */
    private fun HostStore.read(mark: Long) = rowsRead - mark

    @Test
    fun `a large archive answers its first page by reading about one page, and holds nothing per entry`() = runBlocking<Unit> {
        val n = 100_000
        HostStore(dir).use { ArchiveFixtures.fill(it, n) }
        val store = HostStore(dir).also { stores += it }
        val last = ArchiveFixtures.lastRecord(store)
        val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, last) }
        var mark = store.rowsRead
        val first = host.list(NetworkQuery(limit = 100))
        assertEquals(100, first.entries.size)
        assertTrue(store.read(mark) <= 200, "first page read ${store.read(mark)} rows of $n")
        mark = store.rowsRead
        val filtered = host.list(NetworkQuery(limit = 100, filter = NetworkFilter(status = "4xx")))
        assertTrue(filtered.entries.isNotEmpty())
        assertTrue(store.read(mark) <= 200, "a page by recorded fields read ${store.read(mark)} rows of $n")
        assertEquals(n, first.coverage.searched, "how many the history holds is known without counting matches")
        repeat(3) { host.list(NetworkQuery(limit = 100, q = "host:api.example.test method:POST")) }
        mark = store.rowsRead
        host.history()
        assertEquals(0, store.read(mark), "the history's summary reads no rows")
        assertEquals(0, host.heldInMemory(), "nothing is held per entry or per request after answers over $n entries")
    }

    @Test
    fun `an archived epoch answers its pages from disk too`() = runBlocking<Unit> {
        val n = 100_000
        HostStore(dir).use { s ->
            ArchiveFixtures.fill(s, n)
            s.closeEpoch(s.liveEpoch(ArchiveFixtures.DEVICE, ArchiveFixtures.PACKAGE)!!.id, 2, io.yamsergey.dta.protocol.network.EpochCloseReason.CLEARED, 0)
        }
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { error("the app is gone") }
        val all = io.yamsergey.dta.protocol.network.ArchiveScope(all = true)
        var mark = store.rowsRead
        val first = host.list(NetworkQuery(limit = 100, archiveScope = all))
        assertEquals(100, first.entries.size)
        assertEquals(100, first.archived?.size, "every entry marked with its epoch")
        assertTrue(store.read(mark) <= 200, "first archived page read ${store.read(mark)} rows of $n")
        mark = store.rowsRead
        val filtered = host.list(NetworkQuery(limit = 100, archiveScope = all, filter = NetworkFilter(status = "4xx")))
        assertTrue(filtered.entries.isNotEmpty())
        assertTrue(store.read(mark) <= 200, "an archived page by recorded fields read ${store.read(mark)} rows of $n")
        host.search(NetworkQuery(limit = 20, archiveScope = all, q = "item-12"))
        assertEquals(0, host.heldInMemory(), "nothing is held per entry or per request after archive answers over $n entries")
    }

    @Test
    fun `a word finds its page at once once the index is written`() = runBlocking<Unit> {
        val n = 100_000
        HostStore(dir).use { ArchiveFixtures.fill(it, n) }
        val store = HostStore(dir).also { stores += it }
        val last = ArchiveFixtures.lastRecord(store)
        val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, last) }
        while (host.indexWords() > 0) Unit
        assertEquals(0, host.wordsPending())
        host.list(NetworkQuery(limit = 100))
        // Common, rare (a few entries of 100,000), absent; a body filter and a pattern.
        val cases = listOf("tick" to true, "item-12340-0" to true, "nowhere-xyz" to false, "body:item-12340" to true, "body:/item-1234\\d-0/" to true)
        for ((q, some) in cases) {
            host.list(NetworkQuery(limit = 100, q = q))
            val mark = store.rowsRead
            val page = host.list(NetworkQuery(limit = 100, q = q))
            assertEquals(some, page.entries.isNotEmpty(), q)
            // The index names the rows: a word reads the rows of its page (a rare one, a few; an absent one, none), never the archive.
            assertTrue(store.read(mark) <= 200, "«$q» first page read ${store.read(mark)} rows of $n")
        }
        val hits = host.search(NetworkQuery(limit = 20, q = "item-12340-0"))
        assertTrue(hits.entries.isNotEmpty(), "search finds the rare word")
    }
}
