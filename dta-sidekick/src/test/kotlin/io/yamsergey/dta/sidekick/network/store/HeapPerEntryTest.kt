package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.StackFrame
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test

/**
 * 011 T001/T009 (research R0, SC-001): what the store's in-memory history costs. Before 011 it
 * grew by about 1.45 KB per finished entry; now only in-flight entries and a window of recent
 * ones are held, so it stays flat. The numbers are printed for research.md.
 */
class HeapPerEntryTest {
    @TempDir
    lateinit var dir: File

    private val frames = List(40) { i -> StackFrame("io.example.shop.feature$i.Repository", "load$i", "Repository.kt", 100 + i, app = i % 3 == 0) }
    private val requestHeaders = List(12) { Header("x-request-header-$it", "value-$it-" + "a".repeat(24)) }
    private val responseHeaders = List(16) { Header("x-response-header-$it", "value-$it-" + "b".repeat(32)) }

    private fun entry(store: NetworkStore, i: Int) = HttpEntry(
        id = store.nextId(), runId = store.runId, timeMs = 1_000L + i, library = "okhttp3",
        initiator = Initiator("OkHttp Dispatcher", InitiatorSite.ENQUEUE, frames, firstAppFrame = 0),
        method = "POST", url = "https://api.example.com/v1/cart/items/$i?session=abcdef0123456789&page=$i",
        state = HttpState.COMPLETE, status = 200, durationMs = 120,
        requestHeaders = requestHeaders, responseHeaders = responseHeaders,
        requestBody = BodyInfo(BodyPart.REQUEST, 512, 512, "application/json", completeness = Completeness.COMPLETE, preview = "{\"item\":$i," + "\"x\":\"y\",".repeat(30) + "}"),
        responseBody = BodyInfo(BodyPart.RESPONSE, 4096, 4096, "application/json", completeness = Completeness.COMPLETE, preview = "{\"cart\":[" + "{\"id\":1},".repeat(60) + "]}"),
        protocol = "h2",
    )

    private fun used(): Long {
        repeat(4) { System.gc(); Thread.sleep(50) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `retained heap of the history at 1k, 10k and 100k finished entries`() {
        val results = listOf(1_000, 10_000, 100_000).map { count ->
            val root = File(dir, "n$count")
            val before = used()
            val store = NetworkStore(StorageDir(root), pid = 1)
            repeat(count) { store.upsert(entry(store, it)) }
            store.flush()
            val after = used()
            val perEntry = (after - before) / count
            store.close()
            Triple(count, after - before, perEntry)
        }
        results.forEach { (count, total, per) -> println("R0 heap: $count entries -> ${total / 1024} KB retained, ~$per bytes per entry") }
        // SC-001 (T009): past the recent window the history no longer grows with the entries.
        val at10k = results[1].second
        val at100k = results[2].second
        kotlin.test.assertTrue(at100k <= at10k * 1.25 + 512 * 1024, "100k entries hold ${at100k / 1024} KB, 10k hold ${at10k / 1024} KB")
        kotlin.test.assertTrue(at100k < 8L * 1024 * 1024, "the history holds ${at100k / 1024} KB at 100k entries")
    }
}
