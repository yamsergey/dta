package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Feature 069 T001/T018: start, list and memory on archives of [sizes] entries. Run on demand:
 * `DTA_BENCH=100000,1000000 ./gradlew :dta-daemon:test --tests '*ArchiveScaleBench*'`; prints one line per size.
 */
class ArchiveScaleBench {
    @TempDir
    lateinit var dir: File

    private fun heapMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(100) }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) shr 20
    }

    private fun <T> ms(block: () -> T): Pair<T, Long> {
        val t = System.nanoTime()
        return block() to (System.nanoTime() - t) / 1_000_000
    }

    @Test
    fun `start, pages and memory by archive size`() = runBlocking<Unit> {
        val sizes = System.getenv("DTA_BENCH")?.split(',')?.map { it.trim().toInt() }
        assumeTrue(sizes != null, "DTA_BENCH not set")
        sizes!!.forEach { n ->
            val root = File(dir, "a$n")
            val (_, fillMs) = ms { HostStore(root).use { ArchiveFixtures.fill(it, n) } }
            val before = heapMb()
            val store = HostStore(root)
            val (read, readMs) = ms { store.entries(store.liveEpoch(ArchiveFixtures.DEVICE, ArchiveFixtures.PACKAGE)!!.id).size }
            println("BENCH n=$n read epoch: $read entries in ${readMs}ms")
            val last = ArchiveFixtures.entry(n - 1, Random(0))
            val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, last) }
            val (first, startMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100)) } }
            val (_, againMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100)) } }
            val (filtered, filterMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, filter = NetworkFilter(status = "4xx"))) } }
            val (_, hostMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, q = "host:api.example.test method:POST")) } }
            val (_, termMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, q = "item-12")) } }
            val (_, historyMs) = ms { runBlocking { host.history() } }
            val after = heapMb()
            println("BENCH n=$n fill=${fillMs}ms start+first=${startMs}ms (total ${first.total}) again=${againMs}ms 4xx=${filterMs}ms (${filtered.total}) host+POST=${hostMs}ms term=${termMs}ms history=${historyMs}ms heap=${after - before}MB")
            store.close()
        }
    }
}
