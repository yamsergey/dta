package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Feature 067: one entry is decided exactly as a full pass decides it (live events), a pass over a
 * superset of the matches answers as the full pass does (narrowed passes), and a pass can be stopped.
 */
class EntryDecisionTest {
    private fun body(part: BodyPart, stored: Long = 10, preview: String? = null) =
        BodyInfo(part, storedBytes = stored, contentType = "application/json", completeness = Completeness.COMPLETE, preview = preview)

    private val socket = WsConnectionEntry("r1:0", "r1", 0, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
    private val words = listOf("tick", "heartbeat", "price", "token", "expired", "busy", "FAST", "order", "cart", "boom")

    /** A random history: HTTP calls and socket messages, some bodies only previewed (not copied yet). */
    private fun history(seed: Int, size: Int = 120): Pair<List<NetworkEntry>, Map<String, String>> {
        val r = Random(seed)
        val bodies = HashMap<String, String>()
        val out = ArrayList<NetworkEntry>()
        out += socket
        for (n in 1..size) {
            val text = "{\"type\":\"${words.random(r)}\",\"v\":\"${words.random(r)} ${r.nextInt(100)}\"}"
            val copied = r.nextInt(5) != 0
            val id = "r1:$n"
            if (copied) bodies[id] = text
            out += if (r.nextBoolean()) {
                val status = listOf(200, 204, 401, 404, 500, 503).random(r)
                HttpEntry(id, "r1", n * 100L, "okhttp3", method = listOf("GET", "POST").random(r),
                    url = "https://${listOf("api", "telemetry").random(r)}.example.test/v1/${words.random(r)}/$n",
                    state = HttpState.COMPLETE, status = status, responseBody = body(BodyPart.RESPONSE, preview = if (copied) null else text))
            } else {
                WsMessageEntry(id, "r1", n * 100L, "okhttp3-ws", connectionId = socket.id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
                    opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE, preview = if (copied) null else text))
            }
        }
        return out to bodies
    }

    private fun view(entries: List<NetworkEntry>, bodies: Map<String, String>) = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
    )

    private val queries = listOf(
        "tick", "FAST", "tick price", "\"token expired\"", "status:5xx", "status:5xx boom", "-host:telemetry.example.test tick",
        "method:POST", "ws heartbeat", "dir:sent", "host:api.example.test order", "-body:heartbeat", "body:price cart", "url:/cart/",
    )

    @Test
    fun `a single entry is decided as the full pass decides it, with the same hits`() {
        repeat(40) { seed ->
            val (entries, bodies) = history(seed)
            val v = view(entries, bodies)
            for (q in queries) {
                val query = NetworkQuery(q = q, limit = 1000)
                val page = v.list(query)
                val listed = page.entries.map { it.id }.toSet()
                for (e in entries) {
                    val d = v.decide(query, e)
                    assertEquals(e.id in listed, d.match, "seed $seed, '$q', ${e.id}")
                    if (d.match) assertEquals(page.hits?.get(e.id), d.hits, "hits, seed $seed, '$q', ${e.id}")
                }
            }
        }
    }

    @Test
    fun `a pass over any superset of the matches answers as the full pass`() {
        repeat(40) { seed ->
            val (entries, bodies) = history(seed)
            val v = view(entries, bodies)
            val r = Random(seed)
            for (q in queries) {
                val query = NetworkQuery(q = q, limit = 30)
                val full = v.list(query)
                val matched = v.list(query.copy(limit = 1000)).entries.map { it.id }.toSet()
                val superset = matched + entries.filter { r.nextBoolean() }.map { it.id }
                val narrowed = v.list(query, candidates = { it.id in superset })
                assertEquals(full.entries, narrowed.entries, "seed $seed, '$q'")
                assertEquals(full.total to full.next, narrowed.total to narrowed.next, "seed $seed, '$q'")
                assertEquals(full.hits, narrowed.hits, "seed $seed, '$q'")
            }
        }
    }

    @Test
    fun `onMatch sees every match once, with its hit count`() {
        val (entries, bodies) = history(7)
        val v = view(entries, bodies)
        val seen = LinkedHashMap<String, Int>()
        val all = v.list(NetworkQuery(q = "tick", limit = 1000), onMatch = { e, hits -> seen[e.id] = hits })
        assertEquals(all.entries.map { it.id }.toSet(), seen.keys)
        assertEquals(all.total, seen.size)
        all.hits!!.forEach { (id, h) -> assertEquals(h.size, seen[id]) }
    }

    @Test
    fun `a stopped pass throws and answers nothing`() {
        val (entries, bodies) = history(3)
        val v = view(entries, bodies)
        var n = 0
        assertFailsWith<IllegalStateException> { v.list(NetworkQuery(q = "tick"), stop = { if (++n > 10) throw IllegalStateException("superseded") }) }
        assertTrue(n > 10)
    }

    @Test
    fun `a search over a superset of the matches gives the same page and counts`() {
        repeat(20) { seed ->
            val (entries, bodies) = history(seed)
            val v = view(entries, bodies)
            for (q in listOf("tick", "FAST price", "status:5xx boom", "-host:telemetry.example.test tick")) {
                val query = NetworkQuery(q = q, limit = 20)
                val full = v.search(query)
                val matched = v.list(query.copy(limit = 1000)).entries.map { it.id }.toSet()
                val narrowed = v.search(query, candidates = { it.id in matched })
                // The counts of bodies read by preview come from the entries read; narrowing keeps them
                // exact by adding those entries to the candidates (feature 067, research R3).
                assertEquals(full.copy(notSearched = null), narrowed.copy(notSearched = null), "seed $seed, '$q'")
            }
        }
    }
}
