package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 043: Enter takes the line's filter words out to be saved; the rest stays in the line. */
class QueryLineCommitTest {
    private fun commit(q: String) = QueryLine.commit(q, nowMs = 1_000_000_000L)

    @Test
    fun `filter words leave the line, search words stay`() {
        val c = commit("-host:telemetry.example.test status:2xx ACME")
        assertEquals(listOf("-host:telemetry.example.test", "status:2xx"), c.words)
        assertEquals("ACME", c.rest)
    }

    @Test
    fun `bare methods and kinds are filters too`() {
        val c = commit("POST ws token")
        assertEquals(listOf("POST", "ws"), c.words)
        assertEquals("token", c.rest)
    }

    @Test
    fun `words with problems stay in the line`() {
        val c = commit("statsu:4xx -url:/health status:abc")
        assertEquals(listOf("-url:/health"), c.words)
        assertEquals("statsu:4xx status:abc", c.rest)
    }

    @Test
    fun `session-only filters are not saved`() {
        val c = commit("run:current since:5m until:1m -host:a.example.test")
        assertEquals(listOf("-host:a.example.test"), c.words)
        assertEquals("run:current since:5m until:1m", c.rest)
    }

    @Test
    fun `quoted values and phrases keep their quotes`() {
        val c = commit("""body:"a b" "two words"""")
        assertEquals(listOf("""body:"a b""""), c.words)
        assertEquals("\"two words\"", c.rest)
    }

    @Test
    fun `a single word is savable or not`() {
        assertEquals(true, QueryLine.savable("-host:x.example.test"))
        assertEquals(false, QueryLine.savable("since:5m"))
        assertEquals(false, QueryLine.savable("statsu:4xx"))
        assertEquals(false, QueryLine.savable("token"))
        assertEquals(false, QueryLine.savable("status:2xx GET"))
    }
}
