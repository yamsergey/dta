package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryLineTest {
    private val now = 1_000_000_000L
    private fun parse(q: String) = QueryLine.parse(q, nowMs = now)

    @Test
    fun `filters and search words in one line`() {
        val p = parse("""POST status:4xx host:api.example.test token "expired soon"""")
        assertEquals(NetworkFilter(method = "POST", status = "4xx", host = "api.example.test"), p.filter)
        assertEquals(listOf(Term("token", false), Term("expired soon", true)), p.terms)
        assertEquals(emptyList(), p.problems)
    }

    @Test
    fun `bare words for kinds and methods only in upper case`() {
        assertEquals(listOf(EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE), parse("ws").filter.kinds)
        assertEquals(listOf(EntryKind.HTTP), parse("http").filter.kinds)
        assertEquals("GET", parse("GET").filter.method)
        assertEquals(listOf(Term("get", false)), parse("get").terms)
    }

    @Test
    fun `negation becomes an exclusion`() {
        val p = parse("-host:telemetry.example.test -status:2xx -kind:ws")
        assertEquals(
            listOf(
                NetworkFilter(host = "telemetry.example.test"),
                NetworkFilter(status = "2xx"),
                NetworkFilter(kinds = listOf(EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE)),
            ),
            p.exclude,
        )
        assertEquals(NetworkFilter(), p.filter)
        assertEquals(false, parse("-has:intervention").filter.intervened)
        assertEquals(true, parse("has:intervention").filter.intervened)
    }

    @Test
    fun `other keys`() {
        val p = parse("""url:/orders body:"order id" run:current since:5m until:${now - 1000} kind:ws-message""")
        assertEquals(
            NetworkFilter(url = "/orders", bodyContains = "order id", runs = RunScope.CURRENT, sinceMs = now - 300_000, untilMs = now - 1000, kinds = listOf(EntryKind.WS_MESSAGE)),
            p.filter,
        )
    }

    @Test
    fun `a quoted key value pair is text`() {
        assertEquals(listOf(Term("status:4xx", true)), parse("\"status:4xx\"").terms)
        assertEquals(NetworkFilter(), parse("\"status:4xx\"").filter)
    }

    @Test
    fun `problems name the place and a likely key`() {
        val p = parse("stats:4xx status:abc method:GET method:POST -since:5m -foo")
        assertEquals(5, p.problems.size, p.problems.toString())
        val unknown = p.problems[0]
        assertEquals(0 to 9, unknown.start to unknown.end)
        assertTrue("did you mean status:" in unknown.message, unknown.message)
        assertTrue(p.problems[1].message.startsWith("status: "), p.problems[1].message)
        assertTrue("twice" in p.problems[2].message, p.problems[2].message)
        assertTrue("cannot be negated" in p.problems[3].message, p.problems[3].message)
        assertTrue("-body:" in p.problems[4].message, p.problems[4].message)
        // A line with problems is not silently a search for those words.
        assertEquals(emptyList(), p.terms)
    }

    @Test
    fun `an unclosed quote runs to the end`() {
        assertEquals(listOf(Term("token exp", true)), parse("\"token exp").terms)
    }

    @Test
    fun `render gives the line back`() {
        val line = """POST status:4xx host:api.example.test ws has:intervention -host:t.example.test token "expired soon""""
        val p = parse(line)
        assertEquals(line, QueryLine.render(p))
        assertEquals(p, parse(QueryLine.render(p)))
    }

    @Test
    fun `token spans for chips`() {
        val p = parse("POST  token")
        assertEquals(listOf(0 to 4, 6 to 11), p.tokens.map { it.start to it.end })
        assertEquals(listOf("method", null), p.tokens.map { it.key })
    }
}
