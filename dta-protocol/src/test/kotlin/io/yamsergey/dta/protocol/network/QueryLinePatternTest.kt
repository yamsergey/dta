package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 053: `body:/…/`, `req:/…/`, `resp:/…/` in the query line. */
class QueryLinePatternTest {
    private fun parse(q: String) = QueryLine.parse(q, nowMs = 1_000_000L)

    @Test
    fun `slash values of body keys are patterns`() {
        assertEquals(NetworkFilter(bodyPattern = """"id":\s*\d+"""), parse("""body:/"id":\s*\d+/""").filter)
        assertEquals(NetworkFilter(requestPattern = "pass(word)?"), parse("req:/pass(word)?/").filter)
        assertEquals(NetworkFilter(responsePattern = "ok|done"), parse("resp:/ok|done/").filter)
        assertEquals(emptyList(), parse("""body:/"id":\s*\d+/""").problems)
    }

    @Test
    fun `spaces and inner slashes stay inside the pattern`() {
        val p = parse("body:/invalid token/ GET")
        assertEquals("invalid token", p.filter.bodyPattern)
        assertEquals("GET", p.filter.method)
        assertEquals(emptyList(), p.terms)
        assertEquals("a/b", parse("body:/a/b/").filter.bodyPattern)
        assertEquals("""a\/b""", parse("""body:/a\/b/""").filter.bodyPattern)
    }

    @Test
    fun `a value without a closing slash or a quoted one stays literal text`() {
        assertEquals(NetworkFilter(bodyContains = "/v1/users"), parse("body:/v1/users").filter)
        assertEquals(NetworkFilter(bodyContains = "/api/"), parse("body:\"/api/\"").filter)
        assertEquals(NetworkFilter(url = "/api/"), parse("url:/api/").filter)
    }

    @Test
    fun `exclusions take patterns`() {
        val p = parse("""-body:/"type":"tick"/ -resp:/error/""")
        assertEquals(listOf(NetworkFilter(bodyPattern = """"type":"tick""""), NetworkFilter(responsePattern = "error")), p.exclude)
        assertEquals(emptyList(), p.problems)
    }

    @Test
    fun `bad patterns are problems on their word`() {
        val open = parse("GET body:/(abc/")
        assertEquals(1, open.problems.size)
        assertEquals(4, open.problems[0].start)
        assertTrue(open.problems[0].message.startsWith("body: pattern: "), open.problems[0].message)
        assertEquals(null, open.filter.bodyPattern)
        assertTrue(parse("body://").problems.single().message.contains("empty pattern"))
        assertTrue(parse("resp:/" + "a".repeat(1001) + "/").problems.single().message.contains("longer than 1000"))
    }

    @Test
    fun `one body filter per line, whatever its form`() {
        assertTrue(parse("body:/a/ body:b").problems.single().message.contains("given twice"))
        assertTrue(parse("body:/a/ body:/b/").problems.single().message.contains("given twice"))
    }

    @Test
    fun `patterns render back the same and are savable`() {
        listOf("""body:/"id":\s*\d+/""", "req:/a b/", "-resp:/x|y/", "body:\"/api/\"").forEach { q ->
            assertEquals(q, QueryLine.render(parse(q)), q)
            assertTrue(QueryLine.savable(q), q)
        }
    }

    @Test
    fun `tokens cover the whole pattern word`() {
        val t = parse("body:/a b/ x").tokens
        assertEquals(listOf("body:/a b/", "x"), t.map { it.text })
        assertEquals("body", t[0].key)
    }
}
