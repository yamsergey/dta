package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 040: the query line through the history view (list hits, search paging). */
class HistoryQueryTest {
    private fun body(part: BodyPart) = BodyInfo(part, storedBytes = 10, contentType = "application/json", completeness = Completeness.COMPLETE)
    private fun http(n: Int, method: String, host: String, status: Int, response: String) =
        HttpEntry("r1:$n", "r1", n * 100L, "okhttp3", method = method, url = "https://$host/v1/$n", state = HttpState.COMPLETE, status = status, responseBody = body(BodyPart.RESPONSE)) to response

    private val data = listOf(
        http(1, "GET", "api.example.test", 200, """{"ok":true}"""),
        http(2, "POST", "api.example.test", 401, """{"error":"token expired"}"""),
        http(3, "GET", "telemetry.example.test", 200, """{"token":"expired soon"}"""),
        http(4, "POST", "api.example.test", 200, """{"note":"Token Expired again"}"""),
        http(5, "GET", "api.example.test", 500, """{"error":"boom"}"""),
    )
    private val entries = data.map { it.first }
    private val bodies = data.associate { it.first.id to it.second }

    private val view = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
    )

    @Test
    fun `search words leave only the entries that hold them`() {
        val page = view.list(NetworkQuery(q = "-host:telemetry.example.test \"token expired\""))
        assertEquals(listOf("r1:4", "r1:2"), page.entries.map { it.id })
        assertEquals(2, page.total)
        assertEquals(setOf("r1:4", "r1:2"), page.hits!!.keys)
        assertEquals(SearchPlace.RESPONSE_BODY, page.hits!!.getValue("r1:2").single().place)
        // Paging over the matches only.
        val first = view.list(NetworkQuery(q = "expired", limit = 1))
        assertEquals(listOf("r1:4"), first.entries.map { it.id })
        assertEquals(3, first.total)
        assertEquals(listOf("r1:3"), view.list(NetworkQuery(q = "expired", limit = 1, before = first.next)).entries.map { it.id })
    }

    @Test
    fun `all rows keeps every row the filters keep, hits marked`() {
        val page = view.list(NetworkQuery(q = "-host:telemetry.example.test \"token expired\"", allRows = true))
        assertEquals(listOf("r1:5", "r1:4", "r1:2", "r1:1"), page.entries.map { it.id })
        assertEquals(setOf("r1:4", "r1:2"), page.hits!!.keys)
    }

    @Test
    fun `line and filter params together`() {
        val page = view.list(NetworkQuery(filter = NetworkFilter(method = "POST"), q = "status:4xx"))
        assertEquals(listOf("r1:2"), page.entries.map { it.id })
        assertNull(page.hits)
    }

    @Test
    fun `search pages through the entries with hits`() {
        val first = view.search(NetworkQuery(q = "expired", limit = 2, order = Order.OLDEST_FIRST))
        assertEquals(listOf("r1:2", "r1:3"), first.entries.map { it.id })
        assertEquals(3, first.total)
        assertEquals(5, first.searched)
        val second = view.search(NetworkQuery(q = "expired", limit = 2, order = Order.OLDEST_FIRST, before = first.next))
        assertEquals(listOf("r1:4"), second.entries.map { it.id })
        assertNull(second.next)
    }

    @Test
    fun `search needs words, a bad line is refused naming the problem`() {
        assertFailsWith<InvalidParamsException> { view.search(NetworkQuery(q = "status:4xx")) }
        val e = assertFailsWith<InvalidParamsException> { view.list(NetworkQuery(q = "stats:4xx token")) }
        assertTrue("did you mean status:" in e.message!!, e.message)
    }
}
