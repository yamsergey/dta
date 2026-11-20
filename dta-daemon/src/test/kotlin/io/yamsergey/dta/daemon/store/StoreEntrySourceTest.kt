package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.EntryKind
import io.yamsergey.dta.protocol.network.EntrySourceProvider
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.LinkDelivery
import io.yamsergey.dta.protocol.network.LinkEntry
import io.yamsergey.dta.protocol.network.ListEntrySource
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
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
 * Feature 069 A3: the archive read from disk answers exactly as the same history held as a list;
 * what narrows the rows read on disk never drops an entry the query keeps.
 */
class StoreEntrySourceTest {
    @TempDir
    lateinit var dir: File

    private lateinit var store: HostStore

    @AfterEach
    fun close() { if (::store.isInitialized) store.close() }

    private val words = listOf("tick", "heartbeat", "price", "token", "expired", "busy", "FAST", "order", "cart", "boom")
    private val hosts = listOf("api.example.test", "telemetry.example.test", "push.example.test", "cdn.other.test")

    /** One run's random history with ids under [prefix]; bodies some copied, some only previewed. */
    private fun history(r: Random, prefix: String, size: Int, startMs: Long, bodies: MutableMap<String, String>): List<NetworkEntry> {
        val out = ArrayList<NetworkEntry>()
        val sockets = (0 until r.nextInt(1, 3)).map { i ->
            WsConnectionEntry("$prefix:s$i", prefix, startMs + i, "okhttp3-ws", url = "wss://${hosts.random(r)}/live/$i",
                state = listOf(WsState.OPEN, WsState.CLOSED, WsState.FAILED, WsState.CONNECTING).random(r), closeCode = if (r.nextBoolean()) 1000 else null)
        }
        out += sockets
        for (n in 1..size) {
            val text = "{\"type\":\"${words.random(r)}\",\"v\":\"${words.random(r)} ${r.nextInt(100)}\"}"
            val copied = r.nextInt(5) != 0
            val id = "$prefix:$n"
            if (copied) bodies[id] = text
            val body = { part: BodyPart -> BodyInfo(part, storedBytes = text.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = if (copied) null else text.take(12)) }
            out += when (r.nextInt(8)) {
                0 -> LinkEntry(id, prefix, startMs + n * 10, uri = "https://${hosts.random(r)}/open/${words.random(r)}", activity = "Main", delivery = LinkDelivery.COLD_START)
                1, 2, 3 -> HttpEntry(id, prefix, startMs + n * 10, "okhttp3", method = listOf("GET", "POST", "put").random(r),
                    url = "https://${hosts.random(r)}/v1/${words.random(r)}/$n", state = listOf(HttpState.COMPLETE, HttpState.COMPLETE, HttpState.FAILED, HttpState.PENDING).random(r),
                    status = listOf(200, 204, 401, 404, 500, 503, null).random(r), responseBody = if (r.nextInt(4) == 0) null else body(BodyPart.RESPONSE))
                else -> WsMessageEntry(id, prefix, startMs + n * 10, "okhttp3-ws", connectionId = sockets.random(r).id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
                    opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE))
            }
        }
        // A few entries recorded again, as a finished call or a closed socket is.
        repeat(r.nextInt(0, 4)) {
            when (val e = out.random(r)) {
                is HttpEntry -> out += e.copy(status = 200, state = HttpState.COMPLETE)
                is WsConnectionEntry -> out += e.copy(state = WsState.CLOSED, closeCode = 1001)
                else -> Unit
            }
        }
        return out
    }

    private fun randomFilter(r: Random, startMs: Long): NetworkFilter? = if (r.nextInt(4) == 0) null else NetworkFilter(
        kinds = if (r.nextInt(3) == 0) EntryKind.entries.shuffled(r).take(r.nextInt(1, 4)) else null,
        sinceMs = if (r.nextInt(4) == 0) startMs + r.nextLong(0, 400) else null,
        untilMs = if (r.nextInt(4) == 0) startMs + r.nextLong(300, 900) else null,
        method = if (r.nextInt(4) == 0) listOf("get", "POST", "PUT", "DELETE").random(r) else null,
        status = if (r.nextInt(3) == 0) listOf("404", "5xx", "2xx", "failed", "pending", "open", "closed", "connecting", "1000", "1001").random(r) else null,
        host = if (r.nextInt(3) == 0) listOf("api.example.test", "*.example.test", "PUSH.example.test", "*.other.test", "nowhere.test").random(r) else null,
    )

    private fun randomLine(r: Random): String? = when (r.nextInt(5)) {
        0 -> words.random(r)
        1 -> "${words.random(r)} ${words.random(r)}"
        2 -> "-host:${hosts.random(r)}"
        3 -> "dir:${listOf("sent", "received").random(r)} ${words.random(r)}"
        else -> null
    }

    private fun context(entries: List<NetworkEntry>) =
        HistoryContext("r0", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE))

    @Test
    fun `the archive read from disk answers as the same history in a list (1,000 runs)`() {
        store = HostStore(dir)
        repeat(1_000) { run ->
            val r = Random(run)
            val bodies = HashMap<String, String>()
            val epochs = (0 until r.nextInt(1, 4)).map { k ->
                val startMs = 1_000L * k
                val entries = history(r, "r$run-$k", r.nextInt(5, 60), startMs, bodies)
                val epoch = store.openEpoch(DeviceIdentity("dev$run"), "io.example", InstallationInfo("1.$k", k.toLong(), k.toLong(), "aa"), "j$run-$k", startMs)
                entries.chunked(r.nextInt(1, 20)).forEach { store.putEntries(epoch.id, it) }
                epoch.id to entries
            }
            // The same history as one list, in first-record order: each entry where it was first recorded, as it is now.
            val list = LinkedHashMap<String, NetworkEntry>().apply { epochs.forEach { (_, es) -> es.forEach { put(it.id, it) } } }.values.toList()
            val bytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes? = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } }
            val fromList = NetworkHistoryView.over(EntrySourceProvider { ListEntrySource(list) }, { context(list) }, bytes)
            val fromDisk = NetworkHistoryView.over(EntrySourceProvider { f -> store.readSource(epochs.map { it.first }, f) }, { context(list) }, bytes)
            repeat(4) {
                val filter = randomFilter(r, 0)
                var query = NetworkQuery(filter = filter, q = randomLine(r), limit = r.nextInt(1, 15), order = Order.entries.random(r), allRows = r.nextInt(5) == 0)
                repeat(3) {
                    val a = runCatching { fromList.list(query) }
                    val b = runCatching { fromDisk.list(query) }
                    assertEquals(a.getOrNull(), b.getOrNull(), "run $run $query")
                    assertEquals(a.exceptionOrNull()?.message, b.exceptionOrNull()?.message, "run $run $query")
                    query = query.copy(before = a.getOrNull()?.next ?: return@repeat)
                }
                val s = NetworkQuery(filter = filter, q = words.random(r), limit = r.nextInt(1, 10), order = Order.entries.random(r))
                assertEquals(runCatching { fromList.search(s) }.getOrNull(), runCatching { fromDisk.search(s) }.getOrNull(), "run $run search $s")
            }
            list.filterIsInstance<WsConnectionEntry>().take(1).forEach { assertEquals(fromList.entry(it.id), fromDisk.entry(it.id), "run $run entry") }
        }
    }

    @Test
    fun `what narrows the rows on disk keeps every entry the filter keeps`() {
        store = HostStore(dir)
        repeat(300) { run ->
            val r = Random(10_000 + run)
            val entries = history(r, "r$run", 80, 0, HashMap())
            val epoch = store.openEpoch(DeviceIdentity("d$run"), "io.example", InstallationInfo(), "j$run", 0)
            store.putEntries(epoch.id, entries)
            val latest = LinkedHashMap<String, NetworkEntry>().apply { entries.forEach { put(it.id, it) } }.values
            repeat(10) {
                val filter = randomFilter(r, 0) ?: return@repeat
                val eval = NetworkFilterEval(filter, "r$run", { id -> (latest.firstOrNull { it.id == id } as? WsConnectionEntry)?.url }) { _, _ -> null }
                val kept = latest.filter { eval.matches(it) != null }.map { it.id }.toSet()
                val read = store.readSource(listOf(epoch.id), filter).use { src -> src.walk(false, true, null)!!.map { it.id }.toSet() }
                assertTrue(read.containsAll(kept), "run $run $filter: missing ${kept - read}")
            }
        }
    }
}
