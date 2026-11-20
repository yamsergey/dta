package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.EntrySource
import io.yamsergey.dta.protocol.network.EntrySourceProvider
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.IndexText
import io.yamsergey.dta.protocol.network.LinkDelivery
import io.yamsergey.dta.protocol.network.LinkEntry
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Need
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.SearchPage
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 070 B1 (contracts/narrowing.md C2, C3): the archive read through its word index answers
 * as it does read whole — with words written, words not written yet, words written in part,
 * entries changed since, epochs deleted, both orders and several epochs.
 */
class WordIndexEquivalenceTest {
    @TempDir
    lateinit var dir: File

    private lateinit var store: HostStore

    @AfterEach
    fun close() { if (::store.isInitialized) store.close() }

    private val words = listOf("tick", "heartbeat", "price", "token", "expired", "payment", "orders", "item-12", "lineitem123", "boom", "ſtatus", "cart")
    private val hosts = listOf("api.example.test", "telemetry.example.test", "push.example.test")

    private fun text(r: Random) = (0 until r.nextInt(1, 4)).joinToString(" ") { words.random(r) }

    private fun history(r: Random, prefix: String, size: Int, startMs: Long, bodies: MutableMap<Pair<String, BodyPart>, String>): List<NetworkEntry> {
        val out = ArrayList<NetworkEntry>()
        val sockets = (0 until r.nextInt(1, 3)).map { i ->
            WsConnectionEntry("$prefix:s$i", prefix, startMs + i, "okhttp3-ws", url = "wss://${hosts.random(r)}/live/${words.random(r)}", state = WsState.OPEN,
                handshakeHeaders = listOf(Header("Authorization", "Bearer ${words.random(r)}")))
        }
        out += sockets
        for (n in 1..size) {
            val id = "$prefix:$n"
            val body = { part: BodyPart ->
                val t = "{\"type\":\"${words.random(r)}\",\"v\":\"${text(r)}\"}"
                val copied = r.nextInt(4) != 0
                if (copied) bodies[id to part] = t
                BodyInfo(part, t.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = if (copied) null else t.take(r.nextInt(3, 30)))
            }
            out += when (r.nextInt(7)) {
                0 -> LinkEntry(id, prefix, startMs + n * 10, uri = "https://${hosts.random(r)}/open/${words.random(r)}", activity = "Main", delivery = LinkDelivery.COLD_START)
                1, 2, 3 -> HttpEntry(id, prefix, startMs + n * 10, "okhttp3", method = listOf("GET", "POST").random(r), url = "https://${hosts.random(r)}/v1/${words.random(r)}/$n?token=${words.random(r)}",
                    state = HttpState.COMPLETE, status = listOf(200, 404, 500, null).random(r), responseBody = if (r.nextInt(4) == 0) null else body(BodyPart.RESPONSE))
                else -> WsMessageEntry(id, prefix, startMs + n * 10, "okhttp3-ws", connectionId = sockets.random(r).id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
                    opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE))
            }
        }
        return out
    }

    private fun line(r: Random): String = (0 until r.nextInt(1, 3)).joinToString(" ") {
        when (r.nextInt(6)) {
            0 -> words.random(r)
            1 -> words.filter { it.length >= 6 }.random(r).let { it.substring(0, it.length - 1) + "z" }
            2 -> "\"${words.random(r)} ${words.random(r)}\""
            3 -> "body:${words.random(r)}"
            4 -> listOf("ab", "x9", "12").random(r)
            else -> words.random(r).uppercase()
        }
    }

    private fun context() = HistoryContext("r0", Coverage(searched = 0, stored = 0, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE))

    /** The same archive read whole: the need is not given to the store. */
    private fun whole(epochs: () -> List<String>) = object : EntrySourceProvider {
        override fun open(filter: NetworkFilter?): EntrySource = store.readSource(epochs(), filter)
        override fun open(filter: NetworkFilter?, need: Need): EntrySource = store.readSource(epochs(), filter)
    }

    private fun NetworkPage.answer() = copy(coverage = coverage.copy(notSearched = null), degraded = null)
    private fun SearchPage.answer() = copy(notSearched = null, degraded = null)

    @Test
    fun `the archive read through its word index answers as read whole (1,000 runs)`() {
        store = HostStore(dir)
        var narrowed = 0
        repeat(1_000) { run ->
            val r = Random(run)
            store.readPage = if (r.nextBoolean()) r.nextInt(1, 8) else 500
            val bodies = HashMap<Pair<String, BodyPart>, String>()
            val bytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes? = { e, p -> bodies[e.id to p]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } }
            val epochs = (0 until r.nextInt(1, 4)).map { k ->
                val entries = history(r, "r$run-$k", r.nextInt(5, 60), 1_000L * k, bodies)
                val epoch = store.openEpoch(DeviceIdentity("dev$run"), "io.example", InstallationInfo("1.$k", k.toLong(), k.toLong(), "aa"), "j$run-$k", 1_000L * k)
                entries.chunked(r.nextInt(1, 20)).forEach { store.putEntries(epoch.id, it) }
                epoch.id
            }.toMutableList()
            // Words written for most entries, some later changed (pending again), some left pending.
            val pending = store.pendingWords(epochs, 100_000)
            store.putWords(pending.filter { r.nextInt(5) != 0 }.map { p -> IndexText.of(p.entry, bytes).let { WordWrite(p.rowid, p.mark, it.text, it.cut) } })
            repeat(r.nextInt(0, 4)) {
                val e = epochs.random(r)
                store.entries(e).randomOrNull(r)?.let { x -> if (x is HttpEntry) store.putEntries(e, listOf(x.copy(url = x.url + "/${words.random(r)}"))) }
            }
            if (epochs.size > 1 && r.nextInt(4) == 0) {
                val gone = epochs.removeAt(0)
                store.closeEpoch(gone, 1, EpochCloseReason.CLEARED, 0)
                store.delete(DeleteTarget.Epoch(gone))
            }
            val indexed = NetworkHistoryView.over(store.sources { epochs }, ::context, bytes)
            val read = NetworkHistoryView.over(whole { epochs }, ::context, bytes)
            repeat(4) {
                val q = line(r)
                var query = NetworkQuery(q = q, limit = r.nextInt(1, 12), order = Order.entries.random(r), allRows = r.nextInt(6) == 0,
                    filter = if (r.nextInt(4) == 0) NetworkFilter(status = listOf("2xx", "404", "5xx").random(r)) else null)
                repeat(3) {
                    val a = runCatching { read.list(query) }
                    val b = runCatching { indexed.list(query) }
                    assertEquals(a.getOrNull()?.answer(), b.getOrNull()?.answer(), "run $run list $query")
                    assertEquals(a.exceptionOrNull()?.message, b.exceptionOrNull()?.message, "run $run list $query")
                    if ((a.getOrNull()?.entries?.size ?: 0) > 0) narrowed++
                    query = query.copy(before = a.getOrNull()?.next ?: return@repeat)
                }
                var s = NetworkQuery(q = q, limit = r.nextInt(1, 10), order = Order.entries.random(r))
                repeat(3) {
                    val a = runCatching { read.search(s) }
                    val b = runCatching { indexed.search(s) }
                    assertEquals(a.getOrNull()?.answer(), b.getOrNull()?.answer(), "run $run search $s")
                    assertEquals(a.exceptionOrNull()?.message, b.exceptionOrNull()?.message, "run $run search $s")
                    s = s.copy(before = a.getOrNull()?.next ?: return@repeat)
                }
            }
        }
        assertTrue(narrowed > 1_000, "pages with entries: $narrowed")
    }
}
