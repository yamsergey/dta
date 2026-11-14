package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feature 065: the entries a query lets through, for the Timeline, are exactly the ones Network's
 * list keeps for the same query (one evaluator, every key of the language), on every page.
 */
class HistoryPassingTest {
    private fun body(part: BodyPart) = BodyInfo(part, storedBytes = 10, contentType = "application/json", completeness = Completeness.COMPLETE)
    private fun http(n: Int, method: String, host: String, status: Int, response: String, headers: List<Header> = emptyList()) =
        HttpEntry("r1:$n", "r1", n * 100L, "okhttp3", method = method, url = "https://$host/v1/item/$n", state = HttpState.COMPLETE, status = status,
            requestHeaders = headers, responseBody = body(BodyPart.RESPONSE)) to response

    private val socket = WsConnectionEntry("r1:20", "r1", 2_000, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
    private fun tick(n: Int, text: String) = WsMessageEntry("r1:$n", "r1", n * 100L, "okhttp3-ws", connectionId = socket.id, direction = Direction.IN,
        opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE)) to text

    private val data = listOf(
        http(1, "GET", "api.example.test", 200, """{"ok":true}""", listOf(Header("X-Trace", "abc"))),
        http(2, "POST", "api.example.test", 401, """{"error":"token expired"}"""),
        http(3, "GET", "telemetry.example.test", 200, """{"beat":"heartbeat 7"}"""),
        http(4, "POST", "api.example.test", 503, """{"error":"busy"}"""),
        http(5, "GET", "telemetry.example.test", 204, """{}"""),
        http(6, "GET", "api.example.test", 500, """{"error":"boom","id":12345}"""),
        socket to "",
        tick(21, """{"type":"tick","price":1}"""),
        tick(22, """{"type":"heartbeat"}"""),
        tick(23, """{"type":"tick","price":2}"""),
    )
    private val entries = data.map { it.first }
    private val bodies = data.associate { it.first.id to it.second }
    private val view = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, _ -> bodies[e.id]?.takeIf { it.isNotEmpty() }?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
    )

    private fun listed(q: String) = view.list(NetworkQuery(q = q, limit = 500)).entries.map { it.id }.toSet()

    @Test
    fun `every key family lets through what Network's list keeps`() {
        val queries = listOf(
            "-host:telemetry.example.test", "host:api.example.test", "status:5xx", "status:503", "-status:2xx",
            "method:POST", "POST", "url:/item/6", "kind:http", "ws", "-body:heartbeat", "body:/\"id\":\\d+/",
            "header:X-Trace", "expired", "\"token expired\"", "-host:telemetry.example.test status:5xx",
            "-body:heartbeat -host:telemetry.example.test",
        )
        for (q in queries) {
            assertEquals(listed(q), view.passing(NetworkQuery(q = q)).ids, q)
        }
    }

    @Test
    fun `seen is every entry walked, whatever passes`() {
        val p = view.passing(NetworkQuery(q = "status:5xx"))
        assertEquals(entries.map { it.id }.toSet(), p.seen)
        assertEquals(setOf("r1:4", "r1:6"), p.ids)
    }

    @Test
    fun `no query lets everything through`() {
        assertEquals(entries.map { it.id }.toSet(), view.passing(NetworkQuery()).ids)
    }
}
