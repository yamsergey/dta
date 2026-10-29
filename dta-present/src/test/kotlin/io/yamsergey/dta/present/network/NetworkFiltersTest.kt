package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.ops.Catalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NetworkFiltersTest {

    @Test
    fun `every network list filter parameter can be a chip`() {
        val catalog = Catalog["network.list"].params.map { it.name }.toSet()
        assertEquals(setOf("url", "method", "status", "kinds", "intervened", "sinceMs", "untilMs", "bodyContains", "runs"), NetworkFilters.NAMES.toSet())
        assertEquals(NetworkFilters.NAMES.toSet(), NetworkFilters.NAMES.toSet() intersect catalog, "only catalog parameters")
        assertFailsWith<IllegalArgumentException> { NetworkFilters().with("limit", JsonPrimitive(5)) }
    }

    @Test
    fun `chips name each filter and its value, and removing one drops it from the query`() {
        val filters = NetworkFilters(zone = ZoneOffset.UTC)
            .with("url", JsonPrimitive("/v1/cart"))
            .with("status", JsonPrimitive("4xx"))
            .with("kinds", buildJsonArray { add(JsonPrimitive("http")); add(JsonPrimitive("ws-message")) })
            .with("intervened", JsonPrimitive(true))
            .with("sinceMs", JsonPrimitive(1_700_000_000_000))
        assertEquals(
            listOf("URL contains /v1/cart", "Status 4xx", "Kinds http, ws-message", "Scripted only", "Since 22:13:20"),
            filters.chips.map { it.label },
        )
        val without = filters.without("status")
        assertEquals(null, without.params()["status"])
        assertEquals(4, without.chips.size)
    }

    @Test
    fun `the query is what the CLI sends for the same flags`() {
        // dta network list --url /v1/cart --status 4xx --kinds http,ws-message --intervened
        val cli = buildJsonObject {
            put("url", "/v1/cart"); put("status", "4xx")
            put("kinds", buildJsonArray { add(JsonPrimitive("http")); add(JsonPrimitive("ws-message")) })
            put("intervened", true)
        }
        val filters = NetworkFilters()
            .with("intervened", JsonPrimitive(true))
            .with("kinds", JsonArray(listOf(JsonPrimitive("http"), JsonPrimitive("ws-message"))))
            .with("status", JsonPrimitive("4xx"))
            .with("url", JsonPrimitive("/v1/cart"))
        assertEquals(cli, filters.params())
        assertEquals(cli.keys.toList(), filters.params().keys.toList(), "catalog order, as the CLI builds it")
        assertEquals(JsonPrimitive(500), filters.params(limit = 500)["limit"])
    }

    @Test
    fun `the caption says how much is shown and that filters combine with AND`() {
        assertEquals("120 of 340 shown", NetworkFilters().caption(shown = 120, total = 340))
        assertEquals("12 of 40 shown · 2 filters, combined with AND", NetworkFilters().with("url", JsonPrimitive("a")).with("method", JsonPrimitive("GET")).caption(12, 40))
        assertEquals("1 of 1 shown · 1 filter", NetworkFilters().with("url", JsonPrimitive("a")).caption(1, 1))
    }
}
