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

    private fun dbBytes(root: File) = listOf("dta.db", "dta.db-wal").sumOf { File(root, it).length() }

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
            val last = ArchiveFixtures.lastRecord(store)
            val host = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, last) }
            val (first, startMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100)) } }
            val (_, againMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100)) } }
            val (filtered, filterMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, filter = NetworkFilter(status = "4xx"))) } }
            val (_, hostMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, q = "host:api.example.test method:POST")) } }
            val (_, termMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, q = "item-12")) } }
            val (_, historyMs) = ms { runBlocking { host.history() } }
            // Feature 072: what an agent's first page costs with redaction on: the list read redacted (filters, search words and
            // patterns read the masked text), and the masks made for the 100 rows; then the masks alone, per entry.
            runBlocking { host.list(NetworkQuery(limit = 100, redact = true)) }
            val (redactedFirst, redactedMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, redact = true)) } }
            val (_, maskMs) = ms { first.entries.forEach { io.yamsergey.dta.protocol.network.Redactor.entry(it) } }
            val (_, redactedTermMs) = ms { runBlocking { host.list(NetworkQuery(limit = 100, q = "item-12", redact = true)) } }
            val rows = first.entries
            repeat(2_000) { rows.forEach { io.yamsergey.dta.protocol.network.Redactor.entry(it) } }
            val (_, perEntryNs) = ms { repeat(2_000) { rows.forEach { io.yamsergey.dta.protocol.network.Redactor.entry(it) } } }
            println("BENCH n=$n redacted first page=${redactedMs}ms (${redactedFirst.entries.size}) masks for the page=${maskMs}ms term(redacted, no index yet)=${redactedTermMs}ms; Redactor.entry ${perEntryNs * 1000 / (2_000L * rows.size.coerceAtLeast(1))}us/entry")
            val after = heapMb()
            println("BENCH n=$n fill=${fillMs}ms start+first=${startMs}ms (${first.entries.size} of ${first.coverage.searched}) again=${againMs}ms 4xx=${filterMs}ms (${filtered.entries.size}) host+POST=${hostMs}ms term(no index yet)=${termMs}ms history=${historyMs}ms heap=${after - before}MB")
            // Feature 070: the word index written in the background, then words, filters and patterns by it.
            val dbBefore = dbBytes(root)
            val (_, indexMs) = ms { while (host.indexWords() > 0) Unit }
            val bodies = File(root, "bodies").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            println("BENCH n=$n words: indexed in ${indexMs}ms (${n * 1000L / indexMs.coerceAtLeast(1)}/s) index=${store.wordIndexBytes() shr 20}MB db=${dbBefore shr 20}MB→${dbBytes(root) shr 20}MB bodies=${bodies shr 20}MB")
            val cases = listOf("tick", "item-12", "item-123460-0", "item-999980", "nowhere-xyz", "\"symbol S7\"", "price 4242", "body:item-12", "body:/item-12\\d+-3/", "status:4xx item-12")
            val line = cases.joinToString(" ") { q ->
                runBlocking { host.list(NetworkQuery(limit = 100, q = q)) }
                val (page, t) = ms { runBlocking { host.list(NetworkQuery(limit = 100, q = q)) } }
                "«$q»=${t}ms(${page.entries.size})"
            }
            println("BENCH n=$n words: $line")
            // SC-001: 100 words taken from the history, first page each.
            val r = Random(7)
            val sample = (0 until 100).map { "item-${r.nextInt(n / 20) * 20}-0" }
            val times = sample.map { q -> ms { runBlocking { host.list(NetworkQuery(limit = 100, q = q)) } }.second }.sorted()
            val (_, searchMs) = ms { runBlocking { host.search(NetworkQuery(limit = 20, q = "item-123460-0")) } }
            println("BENCH n=$n words: 100 from the history p50=${times[50]}ms p95=${times[95]}ms max=${times.last()}ms; search rare=${searchMs}ms")
            val restarted = HostIndex(store, DeviceIdentity(ArchiveFixtures.DEVICE, "emulator-5554"), ArchiveFixtures.PACKAGE) { ArchiveFixtures.ReadThrough(n, last) }
            val (_, restartMs) = ms { runBlocking { restarted.list(NetworkQuery(limit = 100)) } }
            println("BENCH n=$n words: start+first with the index=${restartMs}ms")
            // Feature 071: streamed searches with the switch on — first row (SC-005), every row of a common word and memory (SC-007).
            SearchRunner().use { runner ->
                fun first(q: String): Pair<Long, Long> {
                    val started = System.nanoTime()
                    var at = -1L
                    var rows = 0L
                    class Enough : RuntimeException()
                    runBlocking {
                        val pass = host.find(NetworkQuery(q = q, archive = true), null)
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(60_000) {
                                FindPipeline.run(runner, Credit(Int.MAX_VALUE), {}, pass.walk) { batch ->
                                    if (at < 0) at = (System.nanoTime() - started) / 1_000_000
                                    rows += batch.size
                                    if (rows >= 100) throw Enough()
                                }
                            }
                        } catch (_: Enough) {
                        }
                    }
                    return at to rows
                }
                val firsts = listOf("item-123460-0", "item-12", "tick", "price 4242", "body:/item-12\\d+-3/").joinToString(" ") { q -> first(q); first(q).let { (t, r) -> "«$q»=${t}ms($r)" } }
                println("BENCH n=$n find archive=on first rows: $firsts")
                val heap0 = heapMb()
                var peak = 0L
                var all = 0L
                val (_, allMs) = ms {
                    runBlocking {
                        val pass = host.find(NetworkQuery(q = "tick", archive = true), null)
                        FindPipeline.run(runner, Credit(Int.MAX_VALUE), {}, pass.walk) { batch ->
                            all += batch.size
                            if (all % 100_000 < batch.size) { val r = Runtime.getRuntime(); peak = maxOf(peak, (r.totalMemory() - r.freeMemory()) shr 20) }
                        }
                    }
                }
                println("BENCH n=$n find archive=on «tick» every row: $all rows in ${allMs}ms, heap before=${heap0}MB peak sampled=${peak}MB after=${heapMb()}MB")
            }
            store.close()
        }
    }
}
