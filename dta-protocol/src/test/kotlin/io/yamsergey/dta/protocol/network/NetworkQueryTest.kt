package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkQueryTest {

    private fun parse(json: String) = NetworkQuery.parse(Json.parseToJsonElement(json).jsonObject)

    private fun rejects(json: String, vararg mentions: String) {
        val e = assertFailsWith<InvalidParamsException> { parse(json) }
        for (m in mentions) assertTrue(m in (e.message ?: ""), "'${e.message}' should mention '$m'")
    }

    @Test
    fun `null params are the defaults`() {
        val q = NetworkQuery.parse(null)
        assertEquals(NetworkQuery(), q)
        assertEquals(100, q.limit)
        assertEquals(Order.NEWEST_FIRST, q.order)
        assertEquals(false, q.redact)
    }

    @Test
    fun `full query parses`() {
        val q = parse(
            """{"filter":{"url":"auth","method":"post","status":"4xx","kinds":["http","ws-message"],
               "sinceMs":10,"untilMs":20,"bodyContains":"account_id","runs":"current"},
               "limit":50,"order":"oldest-first","before":"r1-2:9","redact":true}""",
        )
        assertEquals(
            NetworkQuery(
                filter = NetworkFilter(
                    url = "auth", method = "post", status = "4xx", kinds = listOf(EntryKind.HTTP, EntryKind.WS_MESSAGE),
                    sinceMs = 10, untilMs = 20, bodyContains = "account_id", runs = RunScope.CURRENT,
                ),
                limit = 50, order = Order.OLDEST_FIRST, before = "r1-2:9", redact = true,
            ),
            q,
        )
    }

    @Test
    fun `status accepts exact codes, classes, failed and pending`() {
        for (s in listOf("200", "404", "2xx", "4xx", "5xx", "failed", "pending")) {
            assertEquals(s, parse("""{"filter":{"status":"$s"}}""").filter!!.status)
        }
        rejects("""{"filter":{"status":"bad"}}""", "bad", "4xx", "failed", "pending")
        rejects("""{"filter":{"status":"6xx"}}""", "6xx")
    }

    @Test
    fun `limit outside 1 to 1000 is rejected`() {
        rejects("""{"limit":0}""", "limit", "1000")
        rejects("""{"limit":1001}""", "limit", "1000")
        assertEquals(1000, parse("""{"limit":1000}""").limit)
    }

    @Test
    fun `unknown enum values name the valid ones`() {
        rejects("""{"filter":{"kinds":["http","sse"]}}""", "sse", "http, ws-connection, ws-message")
        rejects("""{"order":"random"}""", "random", "newest-first, oldest-first")
        rejects("""{"filter":{"runs":"previous"}}""", "previous", "current, all")
    }

    @Test
    fun `query encodes back to the same json shape`() {
        val q = NetworkQuery(filter = NetworkFilter(kinds = listOf(EntryKind.WS_CONNECTION)), limit = 5)
        assertEquals(q, NetworkQuery.parse(q.toJson()))
    }
}
