package io.yamsergey.dta.daemon.network.proto

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 013 T001/T002: a source's set and derived mappings, replaced as one; manual state untouched. */
class SchemaSyncTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val registry by lazy { SchemaRegistry(dir) { now++ } }

    private val shop = SchemaOrigin(OriginKind.PROJECT, "/work/shop", "ide")
    private val other = SchemaOrigin(OriginKind.FOLDER, "/work/shop-branch", "cli")

    private val cart = mapOf(
        "shop/v1/cart.proto" to """syntax = "proto3"; package shop.v1; import "shop/v1/item.proto"; message CartState { repeated Item items = 1; }""",
        "shop/v1/item.proto" to """syntax = "proto3"; package shop.v1; message Item { string name = 1; }""",
    )

    private fun derived(type: String = "shop.v1.CartState") =
        DerivedMapping(BodyMappingSpec("*/cart", MappingPart.RESPONSE, schema = "", type = type, method = "GET"), location = "CartApi.kt:12")

    @Test
    fun `a sync registers the source's set with its imports, named by the source`() {
        val result = registry.sync("io.example", shop, cart, listOf(derived()))
        assertEquals(setOf("shop.v1.CartState", "shop.v1.Item"), result.set!!.types.toSet())
        assertEquals(shop.copy(syncedMs = result.set!!.origin!!.syncedMs), result.set!!.origin)
        assertTrue(result.set!!.name.startsWith("project-"))
        val mapping = registry.mappings("io.example").single { !it.builtin }
        assertEquals(result.set!!.name, mapping.schema)
        assertEquals("CartApi.kt:12", mapping.location)
        assertEquals(OriginKind.PROJECT, mapping.origin!!.kind)
        assertEquals(listOf("shop.v1.Item"), result.unmappedTypes)
    }

    @Test
    fun `syncing again replaces only that source - other sources and manual state stay`() {
        registry.sync("io.example", shop, cart, listOf(derived()))
        registry.sync("io.example", other, cart, emptyList())
        registry.addSchema("io.example", "manual", SchemaInput.ProtoSources(cart))
        registry.addMapping("io.example", BodyMappingSpec("*/manual", MappingPart.REQUEST, "manual", "shop.v1.Item"))
        val second = registry.sync("io.example", shop, cart, listOf(derived("shop.v1.Item")))
        assertTrue(second.replaced)
        val sets = registry.schemas("io.example").map { it.name }
        assertEquals(3, sets.size, "$sets")
        val mappings = registry.mappings("io.example").filter { !it.builtin }
        assertEquals(listOf("shop.v1.Item", "shop.v1.Item"), mappings.map { it.type }.sorted(), "the old derived mapping is gone, the manual one stays")
        assertEquals(1, mappings.count { it.origin == null }, "manual")
    }

    @Test
    fun `a broken file is reported, the rest registers, and a failed sync keeps the previous set`() {
        val broken = cart + ("shop/v1/bad.proto" to "this is not proto")
        val result = registry.sync("io.example", shop, broken, emptyList())
        assertEquals(listOf("shop/v1/bad.proto"), result.failed.map { it.file })
        assertTrue(result.failed.single().reason.isNotBlank())
        assertEquals(setOf("shop.v1.CartState", "shop.v1.Item"), result.set!!.types.toSet())

        val nothing = registry.sync("io.example", shop, mapOf("x.proto" to "garbage"), emptyList())
        assertEquals(listOf("x.proto"), nothing.failed.map { it.file })
        assertTrue(nothing.notes.any { "previous set" in it }, "${nothing.notes}")
        assertEquals(result.set!!.name, nothing.set!!.name, "the answer names the set still in use")
        assertEquals(setOf("shop.v1.CartState", "shop.v1.Item"), registry.schemas("io.example").single().types.toSet(), "the previous working set stays")
    }

    @Test
    fun `a derived mapping naming a type the set lacks is not registered, and says so`() {
        val result = registry.sync("io.example", shop, cart, listOf(derived("shop.v1.Nope")))
        assertEquals(1, result.rejectedMappings.size)
        assertTrue("shop.v1.Nope" in result.rejectedMappings.single())
    }

    @Test
    fun `origins survive a restart`() {
        registry.sync("io.example", shop, cart, listOf(derived()))
        val again = SchemaRegistry(dir)
        assertEquals(OriginKind.PROJECT, again.schemas("io.example").single().origin!!.kind)
        assertEquals("CartApi.kt:12", again.mappings("io.example").single { !it.builtin }.location)
    }

    @Test
    fun `a mapping added by hand wins over a derived one, and a decoding names its origin`() {
        registry.sync("io.example", shop, cart, listOf(DerivedMapping(BodyMappingSpec("*/cart", MappingPart.RESPONSE, "", "shop.v1.CartState", method = "GET"), "CartApi.kt:12")))
        val target = BodyTarget("https://api/cart", "GET", MappingPart.RESPONSE, null, "application/x-protobuf")
        val derived = registry.rules("io.example").first().select(target)!!
        assertEquals("shop.v1.CartState", derived.type)
        assertTrue(derived.origin!!.startsWith("project /work/shop, synced "), derived.origin)

        registry.addSchema("io.example", "manual", SchemaInput.ProtoSources(cart))
        registry.addMapping("io.example", BodyMappingSpec("*/cart", MappingPart.RESPONSE, "manual", "shop.v1.Item"))
        val chosen = registry.rules("io.example").first().select(target)!!
        assertEquals("shop.v1.Item", chosen.type, "less specific (no method), but added by hand")
        assertEquals(null, chosen.origin)
    }
}
