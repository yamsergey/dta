package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 069 A2: the history is walked from a source, a page from its cursor; the answer is the
 * one the list gives, page by page, and matches are not counted.
 */
class EntrySourceTest {
    private fun body(part: BodyPart, preview: String? = null) =
        BodyInfo(part, storedBytes = 10, contentType = "application/json", completeness = Completeness.COMPLETE, preview = preview)

    private val socket = WsConnectionEntry("r1:0", "r1", 0, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
    private val words = listOf("tick", "heartbeat", "price", "token", "expired", "busy", "FAST", "order", "cart", "boom")

    private fun history(seed: Int, size: Int = 150): Pair<List<NetworkEntry>, Map<String, String>> {
        val r = Random(seed)
        val bodies = HashMap<String, String>()
        val out = ArrayList<NetworkEntry>()
        out += socket
        for (n in 1..size) {
            val text = "{\"type\":\"${words.random(r)}\",\"v\":\"${words.random(r)} ${r.nextInt(100)}\"}"
            val copied = r.nextInt(5) != 0
            val id = "r1:$n"
            if (copied) bodies[id] = text
            out += when (r.nextInt(7)) {
                0 -> LinkEntry(id, "r1", n * 100L, uri = "app://open/${words.random(r)}", activity = "Main", delivery = LinkDelivery.COLD_START)
                1, 2, 3 -> HttpEntry(id, "r1", n * 100L, "okhttp3", method = listOf("GET", "POST").random(r),
                    url = "https://${listOf("api", "telemetry").random(r)}.example.test/v1/${words.random(r)}/$n",
                    state = HttpState.COMPLETE, status = listOf(200, 204, 401, 404, 500, 503).random(r), responseBody = body(BodyPart.RESPONSE, preview = if (copied) null else text))
                else -> WsMessageEntry(id, "r1", n * 100L, "okhttp3-ws", connectionId = socket.id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
                    opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE, preview = if (copied) null else text))
            }
        }
        return out to bodies
    }

    private fun context(entries: List<NetworkEntry>) = HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE))

    private fun bytes(bodies: Map<String, String>): (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes? = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } }

    /** Today's view: the history as a list. */
    private fun listView(entries: List<NetworkEntry>, bodies: Map<String, String>) =
        NetworkHistoryView({ entries }, { id -> entries.firstOrNull { it.id == id } }, { context(entries) }, bodyBytes = bytes(bodies))

    /** The same history through a source. */
    private fun sourceView(entries: List<NetworkEntry>, bodies: Map<String, String>) =
        NetworkHistoryView.over(EntrySourceProvider { ListEntrySource(entries) }, { context(entries) }, bodyBytes = bytes(bodies))

    private val queries = listOf(
        "", "tick", "FAST", "tick price", "\"token expired\"", "status:5xx", "status:5xx boom", "-host:telemetry.example.test tick",
        "method:POST", "ws heartbeat", "dir:sent", "host:api.example.test order", "-body:heartbeat", "body:price cart", "url:/cart/", "body:/pri.e/",
    )

    @Test
    fun `a source answers exactly as the list, page by page`() {
        repeat(30) { seed ->
            val (entries, bodies) = history(seed)
            val old = listView(entries, bodies)
            val new = sourceView(entries, bodies)
            for (q in queries) for (kinds in listOf(null, NetworkFilter(kinds = listOf(EntryKind.LINK, EntryKind.HTTP)))) for (order in Order.entries) {
                var query = NetworkQuery(q = q.ifEmpty { null }, limit = 7, order = order, filter = kinds)
                repeat(4) {
                    val a = old.list(query)
                    val b = new.list(query)
                    assertEquals(a, b, "seed $seed '$q' $order $kinds")
                    query = query.copy(before = a.next ?: return@repeat)
                }
                if (q.isNotEmpty()) {
                    var s = NetworkQuery(q = q, limit = 5, order = order, filter = kinds)
                    if (runCatching { old.search(s) }.isFailure) {
                        assertFailsWith<InvalidParamsException> { new.search(s) }
                        continue
                    }
                    repeat(3) {
                        val a = old.search(s)
                        val b = new.search(s)
                        assertEquals(a, b, "search seed $seed '$q' $order")
                        s = s.copy(before = a.next ?: return@repeat)
                    }
                }
            }
        }
    }

    @Test
    fun `an unknown cursor is refused as before`() {
        val (entries, bodies) = history(1)
        val v = sourceView(entries, bodies)
        assertFailsWith<InvalidParamsException> { v.list(NetworkQuery(before = "r1:nope")) }
        assertFailsWith<InvalidParamsException> { v.search(NetworkQuery(q = "tick", before = "r1:nope")) }
    }

    @Test
    fun `with no filter and no words, how many were searched is the history, exactly`() {
        val (entries, bodies) = history(5, size = 300)
        val exact = listView(entries, bodies).list(NetworkQuery(limit = 10))
        val page = sourceView(entries, bodies).list(NetworkQuery(limit = 10))
        assertEquals(exact, page)
        assertEquals(entries.count { it !is LinkEntry }, page.coverage.searched, "network views leave links out")
    }
}
