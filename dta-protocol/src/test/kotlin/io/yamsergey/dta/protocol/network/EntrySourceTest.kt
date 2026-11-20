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
 * Feature 069 A2: the history is walked from a source, a page from its cursor and the counts over
 * the whole history under a time budget. Without a budget the answer is today's; with one that
 * runs out, the page is the same and the counts say how far they got.
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

    /** The same history through a source, with a default count budget. */
    private fun sourceView(entries: List<NetworkEntry>, bodies: Map<String, String>, budgetMs: Long?) =
        NetworkHistoryView.over(EntrySourceProvider { ListEntrySource(entries) }, { context(entries) }, bodyBytes = bytes(bodies), countBudgetMs = budgetMs)

    private val queries = listOf(
        "", "tick", "FAST", "tick price", "\"token expired\"", "status:5xx", "status:5xx boom", "-host:telemetry.example.test tick",
        "method:POST", "ws heartbeat", "dir:sent", "host:api.example.test order", "-body:heartbeat", "body:price cart", "url:/cart/", "body:/pri.e/",
    )

    @Test
    fun `without a budget a source answers exactly as the list, page by page`() {
        repeat(30) { seed ->
            val (entries, bodies) = history(seed)
            val old = listView(entries, bodies)
            val new = sourceView(entries, bodies, budgetMs = null)
            for (q in queries) for (kinds in listOf(null, NetworkFilter(kinds = listOf(EntryKind.LINK, EntryKind.HTTP)))) for (order in Order.entries) {
                var query = NetworkQuery(q = q.ifEmpty { null }, limit = 7, order = order, filter = kinds)
                repeat(4) {
                    val a = old.list(query)
                    val b = new.list(query)
                    assertEquals(a, b, "seed $seed '$q' $order $kinds")
                    assertNull(b.counts)
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
    fun `with no time to count, the page is the same and the counts say they are partial`() {
        repeat(30) { seed ->
            val (entries, bodies) = history(seed)
            val exact = listView(entries, bodies)
            val pageOnly = sourceView(entries, bodies, budgetMs = 0)
            for (q in queries) {
                val query = NetworkQuery(q = q.ifEmpty { null }, limit = 7)
                val a = exact.list(query)
                val b = pageOnly.list(query)
                assertEquals(a.entries, b.entries, "seed $seed '$q'")
                assertEquals(a.matches, b.matches)
                assertEquals(a.hits, b.hits)
                assertEquals(a.next, b.next)
                assertEquals(a.fields, b.fields)
                assertEquals(a.coverage.searched, b.coverage.searched, "how many were searched is known without counting")
                if (a.total > b.entries.size) {
                    val counts = b.counts!!
                    assertEquals(false, counts.complete)
                    assertTrue(b.total in b.entries.size..a.total, "a lower bound: ${b.total} of ${a.total}")
                }
            }
        }
    }

    @Test
    fun `a budget asked in the query wins over the default, and a negative one is no limit`() {
        val (entries, bodies) = history(3, size = 400)
        val pageOnly = sourceView(entries, bodies, budgetMs = 0)
        val exact = listView(entries, bodies).list(NetworkQuery(q = "tick", limit = 3))
        assertEquals(exact, pageOnly.list(NetworkQuery(q = "tick", limit = 3, countBudgetMs = -1)))
        val partial = sourceView(entries, bodies, budgetMs = null).list(NetworkQuery(q = "tick", limit = 3, countBudgetMs = 0))
        assertEquals(false, partial.counts?.complete)
    }

    @Test
    fun `an unknown cursor is refused as before`() {
        val (entries, bodies) = history(1)
        val v = sourceView(entries, bodies, budgetMs = null)
        assertFailsWith<InvalidParamsException> { v.list(NetworkQuery(before = "r1:nope")) }
        assertFailsWith<InvalidParamsException> { v.search(NetworkQuery(q = "tick", before = "r1:nope")) }
    }
}
