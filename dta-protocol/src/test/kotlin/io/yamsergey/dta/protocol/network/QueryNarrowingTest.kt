package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 067: a query change is called narrowing only when it is: a pass over the previous query's
 * matches (plus the entries read only in part) answers exactly as the full pass, counts included.
 */
class QueryNarrowingTest {
    private fun narrows(a: String, b: String) = QueryNarrowing.narrows(NetworkQuery(q = a), NetworkQuery(q = b), nowMs = 1_000_000)

    @Test
    fun `provable narrowing`() {
        assertTrue(narrows("FA", "FAST"))
        assertTrue(narrows("tick", "tick price"))
        assertTrue(narrows("tick", "tick status:5xx"))
        assertTrue(narrows("tick", "tick -host:telemetry.example.test"))
        assertTrue(narrows("url:/cart", "url:/cart/items"))
        assertTrue(narrows("status:5xx", "status:5xx method:POST"))
        assertTrue(narrows("id42", "id4271"), "digits match exactly at any length")
        assertTrue(narrows("", "tick"))
        assertTrue(narrows("ws", "ws dir:sent"))
    }

    @Test
    fun `anything unsure is a full pass`() {
        assertFalse(narrows("FAST", "FA"), "shorter word: wider")
        assertFalse(narrows("pay", "payme"), "4 to 5 letters gains a typo: paxme matches payme, not pay")
        assertFalse(narrows("payme", "payment"), "words of 5+ letters match with a typo: not proven")
        assertFalse(narrows("tick price", "tick"), "a word removed")
        assertFalse(narrows("status:5xx", "status:4xx"), "a value changed")
        assertFalse(narrows("-host:a.example.test", ""), "an exclusion removed")
        assertFalse(narrows("tick", "\"tick\""), "a phrase is searched otherwise")
        assertFalse(narrows("body:/x+/", "body:/x+/ tick"), "patterns can run out of time")
        assertFalse(QueryNarrowing.narrows(NetworkQuery(q = "tick", redact = true), NetworkQuery(q = "tick price", redact = true)), "redaction")
        assertFalse(QueryNarrowing.narrows(NetworkQuery(q = "tick"), NetworkQuery(q = "tick price", allRows = true)), "every row")
        assertFalse(narrows("tick", "tick kind:link"), "links are walked only when kinds asks")
    }

    // A random history: some bodies only previewed (not copied here), so the counts of a pass depend on what it reads.
    private val socket = WsConnectionEntry("r1:0", "r1", 0, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
    private val words = listOf("tick", "tock", "price", "prize", "token", "taken", "expired", "busy", "fast", "order", "cart", "boom", "paxme", "payme", "id4271")

    private fun history(seed: Int, size: Int = 150): Triple<List<NetworkEntry>, Map<String, String>, Set<String>> {
        val r = Random(seed)
        val bodies = HashMap<String, String>()
        val partial = HashSet<String>()
        val out = ArrayList<NetworkEntry>()
        out += socket
        for (n in 1..size) {
            val text = "{\"type\":\"${words.random(r)}\",\"v\":\"${words.random(r)} ${words.random(r)} ${r.nextInt(100)}\"}"
            val copied = r.nextInt(4) != 0
            val id = "r1:$n"
            if (copied) bodies[id] = text else partial += id
            fun body(part: BodyPart) = BodyInfo(part, storedBytes = text.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = text.take(30))
            out += if (r.nextBoolean()) {
                HttpEntry(id, "r1", n * 100L, "okhttp3", method = listOf("GET", "POST").random(r),
                    url = "https://${listOf("api", "telemetry").random(r)}.example.test/v1/${words.random(r)}/$n",
                    state = HttpState.COMPLETE, status = listOf(200, 204, 401, 404, 500, 503).random(r), responseBody = body(BodyPart.RESPONSE))
            } else {
                WsMessageEntry(id, "r1", n * 100L, "okhttp3-ws", connectionId = socket.id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
                    opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE))
            }
        }
        return Triple(out, bodies, partial)
    }

    private val pieces = listOf(
        "t", "ti", "tic", "tick", "to", "pr", "pri", "price", "pay", "paym", "payme", "id4", "id42", "id4271", "fa", "fast", "ca", "cart",
        "status:5xx", "status:4xx", "method:POST", "dir:sent", "dir:received", "ws", "http", "-host:telemetry.example.test", "host:api.example.test",
        "url:/v1/", "url:/v1/cart", "body:ti", "body:tick", "-body:boom", "\"tick\"",
    )

    @Test
    fun `whenever narrows is true, the narrowed pass is the full pass, counts included`() {
        var proven = 0
        var cases = 0
        repeat(100) { seed ->
            val (entries, bodies, partial) = history(seed)
            val view = NetworkHistoryView(
                { entries }, { id -> entries.firstOrNull { it.id == id } },
                { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
                bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
            )
            val r = Random(seed * 31 + 7)
            repeat(50) {
                cases++
                val a = List(r.nextInt(0, 3)) { pieces.random(r) }.joinToString(" ")
                // The next query: mostly the previous one with a piece added or a word lengthened, sometimes anything.
                val b = when (r.nextInt(3)) {
                    0 -> "$a ${pieces.random(r)}".trim()
                    1 -> pieces.filter { p -> a.split(' ').any { it.isNotEmpty() && p.startsWith(it) } }.randomOrNull(r)?.let { longer ->
                        val parts = a.split(' ').toMutableList()
                        val i = parts.indexOfFirst { it.isNotEmpty() && longer.startsWith(it) }
                        parts[i] = longer
                        parts.joinToString(" ")
                    } ?: a
                    else -> List(r.nextInt(0, 3)) { pieces.random(r) }.joinToString(" ")
                }
                val qa = NetworkQuery(q = a, limit = 1000)
                val qb = NetworkQuery(q = b, limit = 1000)
                if (runCatching { qa.resolved(); qb.resolved() }.isFailure) return@repeat
                if (!QueryNarrowing.narrows(qa, qb, nowMs = 1_000_000)) return@repeat
                proven++
                val matchedA = view.list(qa).entries.map { it.id }.toSet()
                val full = view.list(qb)
                val narrowed = view.list(qb, candidates = { it.id in matchedA || it.id in partial })
                assertEquals(full, narrowed, "seed $seed: '$a' → '$b'")
                // Every entry the narrower query keeps was kept by the previous one.
                assertTrue(matchedA.containsAll(full.entries.map { it.id }), "seed $seed: '$a' → '$b'")
            }
        }
        assertTrue(proven >= 1000, "only $proven of $cases cases were narrowing: the generator needs more")
    }
}
