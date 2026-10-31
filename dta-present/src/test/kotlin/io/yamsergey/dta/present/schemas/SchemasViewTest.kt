package io.yamsergey.dta.present.schemas

import io.yamsergey.dta.present.StatementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T054: the Schemas view from canned `network.schema.list` / `network.mapping.list` results. */
class SchemasViewTest {
    private fun array(text: String) = Json.parseToJsonElement(text).jsonArray

    private val sets = array("""[{"name":"shop","source":"proto-sources","files":["shop/cart.proto","shop/item.proto"],"types":["shop.Cart","shop.Item"],"addedAtMs":1}]""")
    private val mappings = array(
        """[
        {"id":"m1","urlPattern":"*/v1/cart","part":"response","schema":"shop","type":"shop.Cart","method":"GET"},
        {"id":"m2","urlPattern":"*/v1/old","part":"request","schema":"gone","type":"old.Thing","broken":"schema set 'gone' was removed"},
        {"id":"otlp-1","urlPattern":"*/v1/traces","part":"request","schema":"otlp","type":"opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest","builtin":true}
        ]""",
    )

    @Test
    fun `sets list their files and types`() {
        val view = SchemasView.from(sets, mappings)
        val set = view.sets.single()
        assertEquals("shop", set.name)
        assertEquals(".proto sources · 2 files", set.detail)
        assertEquals(listOf("shop.Cart", "shop.Item"), set.types)
    }

    @Test
    fun `mappings read as which bodies decode with which type`() {
        val rows = SchemasView.from(sets, mappings).mappings
        assertEquals("GET */v1/cart response → shop.Cart (shop)", rows[0].text)
        assertNull(rows[0].broken)
        assertTrue(rows[0].removable)
    }

    @Test
    fun `a broken mapping is stated with its reason`() {
        val broken = SchemasView.from(sets, mappings).mappings[1]
        assertEquals(StatementKind.DEGRADED, broken.broken!!.kind)
        assertEquals("broken: schema set 'gone' was removed", broken.broken!!.detail)
    }

    @Test
    fun `built-in rules are listed but cannot be removed`() {
        val builtin = SchemasView.from(sets, mappings).mappings[2]
        assertTrue(builtin.builtin && !builtin.removable)
    }

    @Test
    fun `the type picker offers every type with its set`() {
        assertEquals(listOf(TypeChoice("shop", "shop.Cart"), TypeChoice("shop", "shop.Item")), SchemasView.from(sets, mappings).types)
    }

    @Test
    fun `nothing added says how to start`() {
        val view = SchemasView.from(array("[]"), array("[]"))
        assertEquals("No schema sets. Add .proto files from the project.", view.empty)
    }

    @Test
    fun `a synced set says where it came from, a derived mapping where in the code`() {
        val sets = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"name":"project-ab","source":"proto-sources","files":["a.proto","b.proto"],"types":["shop.v1.Cart"],"addedAtMs":1,
                "origin":{"kind":"project","path":"/work/shop","client":"ide","syncedMs":0}}]""",
        ) as kotlinx.serialization.json.JsonArray
        val mappings = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"id":"m1","urlPattern":"*/cart","part":"response","schema":"project-ab","type":"shop.v1.Cart","method":"GET",
                "origin":{"kind":"project","path":"/work/shop"},"location":"CartApi.kt:12"},
               {"id":"m2","urlPattern":"*/x","part":"request","schema":"project-ab","type":"shop.v1.Cart"}]""",
        ) as kotlinx.serialization.json.JsonArray
        val view = SchemasView.from(sets, mappings, java.time.ZoneOffset.UTC)
        assertEquals(".proto sources · 2 files · from project /work/shop · synced 00:00:00", view.sets.single().detail)
        assertEquals("CartApi.kt:12", view.mappings.first().location)
        assertTrue(view.mappings.first().text.endsWith("· from code"), view.mappings.first().text)
        assertEquals(null, view.mappings[1].location)
    }
}
