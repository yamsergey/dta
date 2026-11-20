package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 070 A2 (research R2, R3, R10): what the word index holds and what a query needs of it
 * never leave out an entry the query keeps, so an answer that reads only the entries holding what
 * it needs is the answer without the index.
 */
class WordNeedsTest {
    private val words = listOf(
        "order", "orders", "payment", "expired", "heartbeat", "token", "cart", "boom", "item-12", "lineitem123", "a1b2c3", "x9",
        "ſtatus", "Kelvin", "İstanbul", "naïve", "東京駅", "x—y", "Straße", "redacted", "OrderId", "order_id", "price 4242",
    )
    private val hosts = listOf("api.example.test", "cdn.example.test", "push.other.test")

    private class World(val entries: List<NetworkEntry>, val bodies: Map<Pair<String, BodyPart>, NetworkFilterEval.BodyBytes>, val decoded: Map<Pair<String, BodyPart>, String>) {
        val bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes? = { e, p -> bodies[e.id to p] }
        val decode: (NetworkEntry, BodyPart) -> String? = { e, p -> decoded[e.id to p] }
        val texts: Map<String, IndexText.Result> by lazy { entries.associate { it.id to IndexText.of(it, bodyBytes, decode) } }
    }

    private fun text(r: Random): String = (0 until r.nextInt(1, 6)).joinToString(" ") { words.random(r) }

    private fun json(r: Random): String = buildString {
        append("{\"type\":\"").append(words.random(r)).append("\",\"v\":\"").append(text(r))
        if (r.nextInt(4) == 0) append(listOf("\\n", "\\u0001", "\\t").random(r)).append(words.random(r))
        if (r.nextInt(4) == 0) append("\\u00e9").append(words.random(r))
        append("\",\"n\":").append(r.nextInt(10_000)).append('}')
    }

    private fun world(r: Random, prefix: String): World {
        val bodies = HashMap<Pair<String, BodyPart>, NetworkFilterEval.BodyBytes>()
        val decoded = HashMap<Pair<String, BodyPart>, String>()
        val out = ArrayList<NetworkEntry>()
        fun body(id: String, part: BodyPart): BodyInfo {
            if (r.nextInt(4) == 0) {
                // Binary: bytes with words in them; some decoded by a schema.
                val bytes = (text(r) + String(ByteArray(r.nextInt(0, 8)) { r.nextInt(256).toByte() }, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
                if (r.nextInt(3) != 0) bodies[id to part] = NetworkFilterEval.BodyBytes(bytes, r.nextInt(8) != 0)
                if (r.nextBoolean()) decoded[id to part] = json(r)
                return BodyInfo(part, bytes.size.toLong(), contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE)
            }
            val t = if (r.nextBoolean()) json(r) else text(r)
            val size = t.toByteArray(Charsets.UTF_8).size.toLong()
            return when (r.nextInt(4)) {
                0 -> BodyInfo(part, size, contentType = "application/json", completeness = Completeness.COMPLETE, preview = t.take(r.nextInt(1, 20)))
                1 -> BodyInfo(part, size, contentType = "application/json", completeness = Completeness.COMPLETE, preview = t)
                else -> {
                    bodies[id to part] = NetworkFilterEval.BodyBytes(t.toByteArray(Charsets.UTF_8), r.nextInt(10) != 0)
                    BodyInfo(part, size, contentType = listOf("application/json", "text/plain", "application/x-www-form-urlencoded").random(r), completeness = Completeness.COMPLETE)
                }
            }
        }
        fun headers() = buildList {
            if (r.nextBoolean()) add(Header("Authorization", "Bearer ${words.random(r)}"))
            if (r.nextBoolean()) add(Header("X-Trace", text(r)))
        }
        val sockets = (0 until r.nextInt(1, 3)).map { i ->
            WsConnectionEntry("$prefix:s$i", prefix, i.toLong(), "okhttp3-ws", url = "wss://${hosts.random(r)}/live/${words.random(r)}" + if (r.nextBoolean()) "?token=${words.random(r)}" else "",
                state = WsState.OPEN, handshakeHeaders = headers())
        }
        out += sockets
        for (n in 1..r.nextInt(5, 40)) {
            val id = "$prefix:$n"
            out += when (r.nextInt(6)) {
                0 -> LinkEntry(id, prefix, n * 10L, uri = "app://open/${words.random(r)}?q=${words.random(r)}", activity = "Main", delivery = LinkDelivery.COLD_START)
                1, 2 -> HttpEntry(id, prefix, n * 10L, "okhttp3", method = "POST", url = "https://${hosts.random(r)}/v1/${words.random(r)}" + if (r.nextBoolean()) "?token=${words.random(r)}&q=${words.random(r)}" else "",
                    state = HttpState.COMPLETE, status = 200, requestHeaders = headers(), responseHeaders = headers(),
                    requestBody = if (r.nextBoolean()) body(id, BodyPart.REQUEST) else null, responseBody = if (r.nextInt(4) != 0) body(id, BodyPart.RESPONSE) else null,
                    appRequest = if (r.nextInt(5) == 0) HttpRequestSnapshot("https://${hosts.random(r)}/app", "POST", headers(), body(id, BodyPart.APP_REQUEST)) else null)
                else -> WsMessageEntry(id, prefix, n * 10L, "okhttp3-ws", connectionId = sockets.random(r).id, direction = Direction.IN, opcode = MessageOpcode.TEXT,
                    payload = body(id, BodyPart.MESSAGE),
                    envelope = if (r.nextBoolean()) Envelope("signalr", kind = listOf("invocation", "ping", words.random(r)).random(r), topic = words.random(r), id = "${r.nextInt(100)}") else null)
            }
        }
        return World(out, bodies, decoded)
    }

    /** Something to search for: words, pieces of what entries hold, typos, short and non-ASCII ones, phrases. */
    private fun term(r: Random, w: World): String {
        val source = w.entries.random(r).let { e -> (e as? HttpEntry)?.url ?: (e as? WsConnectionEntry)?.url ?: (e as? LinkEntry)?.uri ?: (w.bodies.values.randomOrNull(r)?.let { String(it.bytes, Charsets.UTF_8) }) ?: "order" }
        return when (r.nextInt(8)) {
            0 -> words.random(r)
            1 -> source.substring(r.nextInt(source.length)).take(r.nextInt(3, 12)).filter { it != '"' && it != ':' && !it.isWhitespace() }.ifEmpty { "x" }
            2 -> words.filter { it.length >= 6 }.random(r).let { word ->
                val i = r.nextInt(word.length)
                when (r.nextInt(3)) { 0 -> word.removeRange(i, i + 1); 1 -> word.replaceRange(i, i + 1, "z"); else -> word.substring(0, i) + "q" + word.substring(i) }
            }
            3 -> listOf("ab", "x", "or", "12").random(r)
            4 -> "\"${words.random(r)} ${words.random(r)}\""
            5 -> "redacted"
            6 -> "body:${words.random(r).filter { it != ' ' }}"
            else -> words.random(r).uppercase()
        }
    }

    private fun context(w: World) = HistoryContext("r0", Coverage(searched = 0, stored = w.entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE))

    /** The history as an index-narrowed archive reads it: the walk gives only the candidates; everything else is the whole. */
    private class Narrowed(private val all: ListEntrySource, private val candidates: ListEntrySource) : EntrySource by all {
        override fun walk(newestFirst: Boolean, links: Boolean, after: String?): Sequence<NetworkEntry>? = candidates.walk(newestFirst, links, after)
    }

    private fun narrowed(w: World) = NetworkHistoryView.over(NeedsProvider(w), { context(w) }, w.bodyBytes, w.decode)

    private class NeedsProvider(private val w: World) : EntrySourceProvider {
        override fun open(filter: NetworkFilter?): EntrySource = ListEntrySource(w.entries)
        override fun open(filter: NetworkFilter?, need: Need): EntrySource {
            val all = ListEntrySource(w.entries)
            if (need == Need.Any) return all
            val keep = w.entries.filter { e -> w.texts.getValue(e.id).let { it.cut || WordNeeds.holds(need, it.text) } }
            return Narrowed(all, ListEntrySource(keep))
        }
    }

    /** A page without what counts the bodies read: the index reads fewer of them (contracts/narrowing.md C3). */
    private fun NetworkPage.answer() = copy(coverage = coverage.copy(notSearched = null), degraded = null)
    private fun SearchPage.answer() = copy(notSearched = null, degraded = null)

    @Test
    fun `an entry a query keeps holds what the query needs (1,000 runs)`() {
        var kept = 0
        var left = 0
        repeat(1_000) { run ->
            val r = Random(run)
            val w = world(r, "r$run")
            val view = NetworkHistoryView.over(EntrySourceProvider { ListEntrySource(w.entries) }, { context(w) }, w.bodyBytes, w.decode)
            repeat(6) {
                val line = (0 until r.nextInt(1, 3)).joinToString(" ") { term(r, w) }
                // A filter given as data (an agent's): any text, control characters too, as the decoded fields hold it.
                val needle = if (r.nextInt(3) == 0) w.decoded.values.randomOrNull(r)?.let { j ->
                    val value = kotlinx.serialization.json.Json.parseToJsonElement(j).let { (it as kotlinx.serialization.json.JsonObject)["v"]!!.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content } }
                    val at = r.nextInt(value.length)
                    value.substring(at, minOf(value.length, at + r.nextInt(3, 10)))
                } else null
                val query = NetworkQuery(q = line, redact = r.nextBoolean(), filter = needle?.let { NetworkFilter(bodyContains = it) })
                val (filter, terms) = runCatching { query.resolved() }.getOrNull() ?: return@repeat
                val need = WordNeeds.of(filter, terms, words = true)
                for (e in w.entries) {
                    val d = runCatching { view.decide(query, e) }.getOrNull() ?: continue
                    val t = w.texts.getValue(e.id)
                    if (!d.match) { if (!t.cut && !WordNeeds.holds(need, t.text)) left++; continue }
                    kept++
                    assertTrue(t.cut || WordNeeds.holds(need, t.text), "run $run «$line» redact=${query.redact}: ${e.id} kept but its index text lacks $need\n${t.text}")
                }
            }
        }
        println("WORDNEEDS kept=$kept leftOut=$left")
        assertTrue(kept > 2_000 && left > 20_000, "the runs both keep entries and leave entries out: kept=$kept leftOut=$left")
    }

    @Test
    fun `answers over the entries holding what a query needs are the answers over all (1,000 runs)`() {
        repeat(1_000) { run ->
            val r = Random(50_000 + run)
            val w = world(r, "r$run")
            val full = NetworkHistoryView.over(EntrySourceProvider { ListEntrySource(w.entries) }, { context(w) }, w.bodyBytes, w.decode)
            val indexed = narrowed(w)
            repeat(4) {
                val line = (0 until r.nextInt(1, 3)).joinToString(" ") { term(r, w) }
                var query = NetworkQuery(q = line, limit = r.nextInt(1, 8), order = Order.entries.random(r), redact = r.nextBoolean(), allRows = r.nextInt(5) == 0)
                repeat(3) {
                    val a = runCatching { full.list(query) }
                    val b = runCatching { indexed.list(query) }
                    assertEquals(a.getOrNull()?.answer(), b.getOrNull()?.answer(), "run $run list $query")
                    assertEquals(a.exceptionOrNull()?.message, b.exceptionOrNull()?.message, "run $run list $query")
                    query = query.copy(before = a.getOrNull()?.next ?: return@repeat)
                }
                var s = NetworkQuery(q = line, limit = r.nextInt(1, 8), order = Order.entries.random(r), redact = r.nextBoolean())
                repeat(3) {
                    val a = runCatching { full.search(s) }
                    val b = runCatching { indexed.search(s) }
                    assertEquals(a.getOrNull()?.answer(), b.getOrNull()?.answer(), "run $run search $s")
                    assertEquals(a.exceptionOrNull()?.message, b.exceptionOrNull()?.message, "run $run search $s")
                    s = s.copy(before = a.getOrNull()?.next ?: return@repeat)
                }
            }
        }
    }

    @Test
    fun `what narrows and what does not`() {
        assertEquals(Need.All(setOf("item12")), WordNeeds.term(Term("item-12")))
        assertEquals(Need.All(setOf("tick")), WordNeeds.term(Term("TICK")), "under five letters: exact only")
        assertEquals(Need.Any, WordNeeds.term(Term("price")), "five letters may match one edit away; its left half is two letters")
        assertEquals(Need.Or(listOf(Need.All(setOf("payment")), Need.All(setOf("pay")), Need.All(setOf("ment")))), WordNeeds.term(Term("payment")))
        assertEquals(Need.Any, WordNeeds.term(Term("ab")))
        assertEquals(Need.Any, WordNeeds.term(Term("naïve")), "not ASCII")
        assertEquals(Need.Any, WordNeeds.term(Term("x—y")), "its bytes read as Latin-1 hold letters the word does not")
        assertEquals(Need.All(setOf("orderid")).strings, (WordNeeds.term(Term("order id", phrase = true)) as Need.Or).parts.first().let { (it as Need.All).strings })
        assertEquals(Need.Any, WordNeeds.of(null, listOf(Term("ab"), Term("x")), words = true))
        assertEquals(Need.Any, WordNeeds.of(null, listOf(Term("item-12")), words = false), "allRows: words do not leave rows out")
        assertEquals(Need.All(setOf("item12")), WordNeeds.of(NetworkFilter(bodyContains = "ITEM-12"), emptyList(), words = true))
    }

    @Test
    fun `the index text folds case as ignoreCase and keeps what redaction shows`() {
        assertEquals("status", IndexText.normalise("ſtatus"), "long s is s to a case-insensitive pattern")
        assertEquals("kelvin", IndexText.normalise("Kelvin"))
        val e = HttpEntry("a", "r", 1, "okhttp3", method = "GET", url = "https://api.example.test/x?token=abc", state = HttpState.COMPLETE,
            requestHeaders = listOf(Header("Authorization", "Bearer xyz")))
        val t = IndexText.of(e, { _, _ -> null }).text
        assertTrue("tokenredacted" in t && "tokenabc" in t && "redacted" in t, t)
        val big = "a".repeat(IndexText.PART_CHARS + 1)
        val cut = IndexText.of(e.copy(responseBody = BodyInfo(BodyPart.RESPONSE, big.length.toLong(), completeness = Completeness.COMPLETE)),
            { _, _ -> NetworkFilterEval.BodyBytes(big.toByteArray(), true) })
        assertTrue(cut.cut)
        val notHere = IndexText.of(e.copy(responseBody = BodyInfo(BodyPart.RESPONSE, 900, completeness = Completeness.COMPLETE, preview = "first part")), { _, _ -> null })
        assertTrue(notHere.cut, "a body not copied yet may be read by the next answer")
        assertFalse(IndexText.of(e, { _, _ -> null }).cut)
    }
}
