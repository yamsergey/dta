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
        val (filtered, filterMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, filter = NetworkFilter(status = "4xx"))) } }
        assertTrue(filtered.entries.isNotEmpty())
        assertTrue(filterMs < 100, "a page by recorded fields in $filterMs ms")
        assertEquals(n, first.coverage.searched, "how many the history holds is known without counting matches")
        println("SCALE n=$n first=${startMs}ms filtered=${filterMs}ms history=${first.coverage.searched}")
        repeat(3) { host.list(NetworkQuery(limit = 100, q = "host:api.example.test method:POST")) }
        val held = heapMb() - before
        assertTrue(held < 30, "memory held after answers over $n entries: $held MB")
    }
}
