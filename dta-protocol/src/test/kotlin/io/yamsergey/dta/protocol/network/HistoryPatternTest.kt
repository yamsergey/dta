package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 053: body patterns through the history view (list hits, search, not-searched). */
class HistoryPatternTest {
    private fun body(part: BodyPart) = BodyInfo(part, storedBytes = 10, contentType = "application/json", completeness = Completeness.COMPLETE)
    private fun http(n: Int, response: String) =
        HttpEntry("r1:$n", "r1", n * 100L, "okhttp3", method = "GET", url = "https://api.example.test/v1/$n", state = HttpState.COMPLETE, status = 200, responseBody = body(BodyPart.RESPONSE)) to response

    private val data = listOf(
        http(1, """{"orderId": 7, "status": "ok"}"""),
        http(2, """{"orderId":123,"status":"failed"}"""),
        http(3, """{"note":"no order here"}"""),
        http(4, "a".repeat(200)),
    )
    private val entries = data.map { it.first }
    private val bodies = data.associate { it.first.id to it.second }

    private fun view(budget: () -> PatternBudget = { PatternBudget() }) = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
        patternBudget = budget,
    )

    @Test
    fun `list keeps matches and marks the matched span`() {
        val page = view().list(NetworkQuery(q = """resp:/"orderId":\s*\d+/"""))
        assertEquals(listOf("r1:2", "r1:1"), page.entries.map { it.id })
        val hit = page.hits!!.getValue("r1:2").single()
        assertEquals(SearchPlace.RESPONSE_BODY, hit.place)
        assertEquals(""""orderId":123""", bodies.getValue("r1:2").substring(hit.start, hit.end))
    }

    @Test
    fun `search takes a pattern instead of a word, and words with it`() {
        val v = view()
        val only = v.search(NetworkQuery(q = """body:/"status"\s*:\s*"(failed|rejected)"/"""))
        assertEquals(listOf("r1:2"), only.entries.map { it.id })
        assertEquals("/\"status\"\\s*:\\s*\"(failed|rejected)\"/", only.entries.single().hits.single().term)
        val both = v.search(NetworkQuery(q = "body:/orderid/ ok"))
        assertEquals(listOf("r1:1"), both.entries.map { it.id })
        assertEquals(setOf("/orderid/", "ok"), both.entries.single().hits.map { it.term }.toSet())
    }

    @Test
    fun `bodies cut by the budget are stated`() {
        val page = view { PatternBudget(perBodyMs = 20, totalMs = 500) }.list(NetworkQuery(q = "body:/(.*a){12}x/"))
        assertEquals(emptyList<String>(), page.entries.map { it.id })
        assertTrue(page.coverage.notSearched!!.any { it.startsWith("bodies skipped: pattern too slow (1)") }, page.coverage.notSearched.toString())
    }
}
