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
 * Feature 069 US1/US4: an archive's size does not set how long the first answer takes or how much
 * memory the daemon holds. 100,000 entries here (the million is the benchmark's, D1).
 */
class HostIndexScaleTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun heapMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(50) }
        return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }
    }

    private fun <T> ms(block: () -> T): Pair<T, Long> {
        val t = System.nanoTime()
        return block() to (System.nanoTime() - t) / 1_000_000
    }

    /**
     * A repeatable answer's time: the best of [times] runs. One run beside a loaded machine (an
     * IDE, an emulator under traffic) took twice the budget; the best run is the code's own time.
     */
    private fun <T> best(times: Int = 5, block: () -> T): Pair<T, Long> =
        (1..times).map { ms(block) }.minBy { it.second }

    @Test
    fun `a large archive answers its first page at once and is not held in memory`() = runBlocking<Unit> {
        val n = 100_000
        HostStore(dir).use { ArchiveFixtures.fill(it, n) }
        val store = HostStore(dir).also { stores += it }
        val before = heapMb()
        val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, ArchiveFixtures.entry(n - 1, Random(0))) }
        val (first, startMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100)) } }
        assertEquals(100, first.entries.size)
        assertTrue(startMs < 150, "first page, after a start, in $startMs ms")
        val (filtered, filterMs) = best { runBlocking { host.list(NetworkQuery(limit = 100, filter = NetworkFilter(status = "4xx"))) } }
        assertTrue(filtered.entries.isNotEmpty())
        assertTrue(filterMs < 100, "a page by recorded fields in $filterMs ms")
        assertEquals(n, first.coverage.searched, "how many the history holds is known without counting matches")
        println("SCALE n=$n first=${startMs}ms filtered=${filterMs}ms history=${first.coverage.searched}")
        repeat(3) { host.list(NetworkQuery(limit = 100, q = "host:api.example.test method:POST")) }
        host.history()
        val (_, historyMs) = best { runBlocking { host.history() } }
        assertTrue(historyMs < 30, "the history's summary in $historyMs ms")
        val held = heapMb() - before
        assertTrue(held < 30, "memory held after answers over $n entries: $held MB")
    }

    @Test
    fun `an archived epoch answers its pages from disk too`() = runBlocking<Unit> {
        val n = 100_000
        HostStore(dir).use { s ->
            ArchiveFixtures.fill(s, n)
            s.closeEpoch(s.liveEpoch(ArchiveFixtures.DEVICE, ArchiveFixtures.PACKAGE)!!.id, 2, io.yamsergey.dta.protocol.network.EpochCloseReason.CLEARED, 0)
        }
        val store = HostStore(dir).also { stores += it }
        val before = heapMb()
        val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { error("the app is gone") }
        val all = io.yamsergey.dta.protocol.network.ArchiveScope(all = true)
        val (first, firstMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, archive = all)) } }
        assertEquals(100, first.entries.size)
        assertEquals(100, first.archived?.size, "every entry marked with its epoch")
        assertTrue(firstMs < 150, "first archived page in $firstMs ms")
        val (filtered, filterMs) = best { runBlocking { host.list(NetworkQuery(limit = 100, archive = all, filter = NetworkFilter(status = "4xx"))) } }
        assertTrue(filtered.entries.isNotEmpty())
        assertTrue(filterMs < 100, "an archived page by recorded fields in $filterMs ms")
        val (_, searchMs) = ms { runBlocking { host.search(NetworkQuery(limit = 20, archive = all, q = "item-12")) } }
        println("SCALE archive n=$n first=${firstMs}ms filtered=${filterMs}ms search=${searchMs}ms")
        val held = heapMb() - before
        assertTrue(held < 30, "memory held after archive answers over $n entries: $held MB")
    }

    @Test
    fun `a word finds its page at once once the index is written`() = runBlocking<Unit> {
        val n = 100_000
        HostStore(dir).use { ArchiveFixtures.fill(it, n) }
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, ArchiveFixtures.entry(n - 1, Random(0))) }
        val (_, indexMs) = ms { while (host.indexWords() > 0) Unit }
        assertEquals(0, host.wordsPending())
        host.list(NetworkQuery(limit = 100))
        // Common, rare (a few entries of 100,000), absent; a body filter and a pattern.
        val cases = listOf("tick" to true, "item-12340-0" to true, "nowhere-xyz" to false, "body:item-12340" to true, "body:/item-1234\\d-0/" to true)
        val times = cases.map { (q, some) ->
            host.list(NetworkQuery(limit = 100, q = q))
            val (page, t) = best { runBlocking { host.list(NetworkQuery(limit = 100, q = q)) } }
            assertEquals(some, page.entries.isNotEmpty(), q)
            assertTrue(t < 300, "«$q» first page in $t ms")
            q to t
        }
        val (hits, searchMs) = best { runBlocking { host.search(NetworkQuery(limit = 20, q = "item-12340-0")) } }
        assertTrue(hits.entries.isNotEmpty() && searchMs < 300, "search in $searchMs ms")
        println("SCALE words n=$n index=${indexMs}ms ${times.joinToString { "${it.first}=${it.second}ms" }} search=${searchMs}ms")
    }
}
