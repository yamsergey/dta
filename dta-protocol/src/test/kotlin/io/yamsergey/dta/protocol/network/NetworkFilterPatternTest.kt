package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 053: evaluating body patterns. */
class NetworkFilterPatternTest {
    private fun body(part: BodyPart, binary: Boolean = false) =
        BodyInfo(part, storedBytes = 10, contentType = if (binary) "application/x-protobuf" else "application/json", binary = binary, completeness = Completeness.COMPLETE)

    private val order = HttpEntry("a:1", "a", 100, "okhttp3", method = "POST", url = "https://api.example.test/order", state = HttpState.COMPLETE, status = 200,
        requestBody = body(BodyPart.REQUEST), responseBody = body(BodyPart.RESPONSE))
    private val quote = HttpEntry("a:2", "a", 200, "okhttp3", method = "GET", url = "https://api.example.test/quote", state = HttpState.COMPLETE, status = 200,
        responseBody = body(BodyPart.RESPONSE))
    private val proto = HttpEntry("a:3", "a", 300, "okhttp3", method = "GET", url = "https://api.example.test/p", state = HttpState.COMPLETE, status = 200,
        responseBody = body(BodyPart.RESPONSE, binary = true))
    private val raw = HttpEntry("a:4", "a", 400, "okhttp3", method = "GET", url = "https://api.example.test/r", state = HttpState.COMPLETE, status = 200,
        responseBody = body(BodyPart.RESPONSE, binary = true))
    private val tick = WsMessageEntry("a:5", "a", 500, "okhttp3-ws", connectionId = "a:9", direction = Direction.IN, opcode = MessageOpcode.TEXT,
        payload = body(BodyPart.MESSAGE))

    private val all = listOf(order, quote, proto, raw, tick)

    private val texts = mapOf(
        (order.id to BodyPart.REQUEST) to """{"Password": "hunter2", "qty": 3}""",
        (order.id to BodyPart.RESPONSE) to """{"orderId": 12345, "status": "Rejected"}""",
        (quote.id to BodyPart.RESPONSE) to """{"symbol":"ACME","price":19.57}""",
        (proto.id to BodyPart.RESPONSE) to "\u0008\u0001",
        (raw.id to BodyPart.RESPONSE) to "\u0000\u0001orderId",
        (tick.id to BodyPart.MESSAGE) to """{"type":"tick","symbol":"ACME"}""",
    )

    private fun eval(f: NetworkFilter, budget: PatternBudget = PatternBudget()) = NetworkFilterEval(
        filter = f,
        liveRunId = "a",
        connectionUrl = { "wss://push.example.test/s" },
        decoded = { e, _ -> if (e.id == proto.id) """{"items":[{"name":"Widget 42"}]}""" else null },
        budget = budget,
        bodyBytes = { entry, part -> texts[entry.id to part]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(Charsets.ISO_8859_1), true) } },
    )

    private fun ids(f: NetworkFilter) = eval(f).let { e -> all.filter { e.matches(it) != null }.map { it.id } }

    @Test
    fun `a pattern is found anywhere in a body, case ignored`() {
        assertEquals(listOf(order.id), ids(NetworkFilter(bodyPattern = """"orderid":\s*\d+""")))
        assertEquals(listOf(order.id), ids(NetworkFilter(bodyPattern = "status\": \"(failed|rejected)\"")))
        assertEquals(listOf(quote.id, tick.id), ids(NetworkFilter(bodyPattern = "acme")))
    }

    @Test
    fun `inline flags make it case-sensitive`() {
        assertEquals(emptyList<String>(), ids(NetworkFilter(bodyPattern = "(?-i)acme")))
        assertEquals(listOf(quote.id, tick.id), ids(NetworkFilter(bodyPattern = "(?-i)ACME")))
    }

    @Test
    fun `request and response patterns look in one body`() {
        assertEquals(listOf(order.id), ids(NetworkFilter(requestPattern = """password"\s*:""")))
        assertEquals(emptyList<String>(), ids(NetworkFilter(responsePattern = "password")))
        assertEquals(listOf(order.id, quote.id, proto.id), ids(NetworkFilter(responsePattern = """\d{2,}""")))
    }

    @Test
    fun `exclusions leave matches out`() {
        assertEquals(listOf(order.id, quote.id, proto.id, raw.id), ids(NetworkFilter(exclude = listOf(NetworkFilter(bodyPattern = """"type":"tick"""")))))
    }

    @Test
    fun `decoded binary bodies are matched, raw binary ones are not and are counted`() {
        val e = eval(NetworkFilter(bodyPattern = """widget \d+|orderid"""))
        assertEquals(listOf(order.id, proto.id), all.filter { e.matches(it) != null }.map { it.id })
        assertEquals(listOf("items[0].name"), e.fieldPaths[proto.id].orEmpty().toList())
        assertEquals(1, e.patternBinary)
    }

    @Test
    fun `the match is recorded with its span`() {
        val e = eval(NetworkFilter(responsePattern = """"price":\d+\.\d+"""))
        e.matches(quote)
        val hit = e.patternHits[quote.id]!!.single()
        assertEquals(SearchPlace.RESPONSE_BODY, hit.place)
        assertEquals("/\"price\":\\d+\\.\\d+/", hit.term)
        assertEquals("\"price\":19.57", texts[quote.id to BodyPart.RESPONSE]!!.substring(hit.start, hit.end))
        assertTrue(hit.excerpt.text.contains("19.57"))
    }

    @Test
    fun `a runaway pattern is cut by the budget and counted`() {
        val slow = "a".repeat(200)
        val many = (1..20).map { i ->
            WsMessageEntry("b:$i", "a", i.toLong(), "okhttp3-ws", connectionId = "a:9", direction = Direction.IN, opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE))
        }
        val budget = PatternBudget(perBodyMs = 20, totalMs = 200)
        val e = NetworkFilterEval(NetworkFilter(bodyPattern = "(.*a){12}x"), "a", { null }, budget = budget,
            bodyBytes = { _, _ -> NetworkFilterEval.BodyBytes(slow.toByteArray(), true) })
        val started = System.nanoTime()
        val kept = many.filter { e.matches(it) != null }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(emptyList<String>(), kept.map { it.id })
        assertEquals(20, budget.skipped)
        assertTrue(tookMs < 1_500, "took $tookMs ms")
    }
}
