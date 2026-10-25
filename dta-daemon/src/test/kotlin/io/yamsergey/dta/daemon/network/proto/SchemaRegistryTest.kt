package io.yamsergey.dta.daemon.network.proto

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingOutcome
import io.yamsergey.dta.protocol.network.Direction
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaRegistryTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private fun registry() = SchemaRegistry(dir) { now++ }
    private val pkg = "com.example.app"

    private val fixture: Map<String, String> = run {
        val root = File(checkNotNull(javaClass.getResource("/proto/fixture.proto")).toURI()).parentFile
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".proto") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }
    private val sources = SchemaInput.ProtoSources(fixture)

    private fun http(url: String, method: String = "POST", part: MappingPart = MappingPart.REQUEST, contentType: String? = "application/x-protobuf") =
        BodyTarget(url, method, part, null, contentType)

    private fun select(r: SchemaRegistry, target: BodyTarget) = r.rules(pkg).firstNotNullOfOrNull { it.select(target) }

    private fun info(bytes: ByteArray) = BodyInfo(BodyPart.REQUEST, bytes.size.toLong(), binary = true, completeness = Completeness.COMPLETE)

    @Test
    fun `schema sets are added, listed, replaced and removed`() {
        val r = registry()
        val set = r.addSchema(pkg, "fixture", sources)
        assertEquals(SchemaSource.PROTO_SOURCES, set.source)
        assertEquals(listOf("fixture.proto", "fixture/v1/common.proto"), set.files.sorted())
        assertTrue("fixture.v1.Order" in set.types && "fixture.v1.Order.Status" !in set.types && "fixture.v1.Common" in set.types, "${set.types}")
        assertEquals(listOf("fixture"), r.schemas(pkg).map { it.name })
        r.addSchema(pkg, "fixture", sources)
        assertEquals(1, r.schemas(pkg).size, "re-adding replaces")
        r.removeSchema(pkg, "fixture")
        assertEquals(emptyList(), r.schemas(pkg))
        assertFailsWith<SchemaException> { r.removeSchema(pkg, "fixture") }
    }

    @Test
    fun `reserved and invalid names are refused`() {
        val r = registry()
        assertTrue("reserved" in assertFailsWith<SchemaException> { r.addSchema(pkg, "otlp", sources) }.message!!)
        assertFailsWith<SchemaException> { r.addSchema(pkg, "../x", sources) }
        assertFailsWith<SchemaException> { r.addSchema(pkg, "", sources) }
    }

    @Test
    fun `mappings are validated`() {
        val r = registry()
        r.addSchema(pkg, "fixture", sources)
        assertTrue("unknown schema" in assertFailsWith<SchemaException> { r.addMapping(pkg, BodyMappingSpec("*/x", MappingPart.REQUEST, "nope", "fixture.v1.Order")) }.message!!)
        val unknownType = assertFailsWith<SchemaException> { r.addMapping(pkg, BodyMappingSpec("*/x", MappingPart.REQUEST, "fixture", "fixture.v1.Ordr")) }
        assertTrue("fixture.v1.Order" in unknownType.message!!, "close matches offered: ${unknownType.message}")
        assertFailsWith<SchemaException> { r.addMapping(pkg, BodyMappingSpec("*/x", MappingPart.REQUEST, "fixture", "fixture.v1.Order", direction = Direction.OUT)) }
        assertFailsWith<SchemaException> { r.addMapping(pkg, BodyMappingSpec("*/x", MappingPart.MESSAGE, "fixture", "fixture.v1.ChatMessage", method = "POST")) }
        assertFailsWith<SchemaException> { r.addMapping(pkg, BodyMappingSpec("", MappingPart.REQUEST, "fixture", "fixture.v1.Order")) }
    }

    @Test
    fun `globs match the full URL with method, part and direction`() {
        val r = registry()
        r.addSchema(pkg, "fixture", sources)
        val m = r.addMapping(pkg, BodyMappingSpec("http://*/proto/order", MappingPart.REQUEST, "fixture", "fixture.v1.Order", method = "post"))
        assertEquals(m.id, select(r, http("http://127.0.0.1:8099/proto/order"))!!.rule)
        assertNull(select(r, http("http://127.0.0.1:8099/proto/order?x=1")), "full-URL match")
        assertNull(select(r, http("http://127.0.0.1:8099/proto/order", method = "PUT")))
        assertNull(select(r, http("http://127.0.0.1:8099/proto/order", part = MappingPart.RESPONSE)))
        val ws = r.addMapping(pkg, BodyMappingSpec("ws://*/ws", MappingPart.MESSAGE, "fixture", "fixture.v1.ChatMessage", direction = Direction.OUT))
        assertEquals(ws.id, select(r, BodyTarget("ws://127.0.0.1:8098/ws", null, MappingPart.MESSAGE, Direction.OUT, null))!!.rule)
        assertNull(select(r, BodyTarget("ws://127.0.0.1:8098/ws", null, MappingPart.MESSAGE, Direction.IN, null)))
    }

    @Test
    fun `user mappings beat built-in OTLP, the most literal pattern wins, then the most recent`() {
        val r = registry()
        r.addSchema(pkg, "fixture", sources)
        val traces = http("http://c:4318/v1/traces")
        assertEquals("builtin:otlp-traces", select(r, traces)!!.rule)
        val user = r.addMapping(pkg, BodyMappingSpec("*", MappingPart.REQUEST, "fixture", "fixture.v1.Order"))
        assertEquals(user.id, select(r, traces)!!.rule)
        val literal = r.addMapping(pkg, BodyMappingSpec("*/v1/traces", MappingPart.REQUEST, "fixture", "fixture.v1.Item"))
        r.addMapping(pkg, BodyMappingSpec("http://*", MappingPart.REQUEST, "fixture", "fixture.v1.Card"))
        assertEquals(literal.id, select(r, traces)!!.rule)
        val same = r.addMapping(pkg, BodyMappingSpec("*/v1/traces", MappingPart.REQUEST, "fixture", "fixture.v1.Card"))
        assertEquals(same.id, select(r, traces)!!.rule, "tie → most recent")
        assertEquals("fixture.v1.Card", select(r, traces)!!.type)
    }

    @Test
    fun `listing includes the built-in OTLP rules`() {
        val r = registry()
        val builtin = r.mappings(pkg).filter { it.builtin }
        assertEquals(6, builtin.size)
        assertTrue(builtin.all { it.schema == "otlp" && it.method == "POST" })
    }

    @Test
    fun `schemas and mappings persist across registries`() {
        registry().apply {
            addSchema(pkg, "fixture", sources)
            addMapping(pkg, BodyMappingSpec("*/proto/order", MappingPart.REQUEST, "fixture", "fixture.v1.Order"))
        }
        val r = registry()
        assertEquals(listOf("fixture"), r.schemas(pkg).map { it.name })
        val decoded = BodyDecoding(r.rules(pkg)).decode(http("http://h/proto/order"), FixtureWire.order(), info(FixtureWire.order()))
        assertEquals(DecodingOutcome.DECODED, decoded.info.outcome, "${decoded.info}")
        assertTrue(File(dir, "schemas/$pkg/fixture.desc").isFile)
        val next = r.addMapping(pkg, BodyMappingSpec("*/a", MappingPart.REQUEST, "fixture", "fixture.v1.Item"))
        assertEquals("m2", next.id, "ids continue after a restart")
    }

    @Test
    fun `removing or replacing a schema breaks its mappings, visibly`() {
        val r = registry()
        r.addSchema(pkg, "fixture", sources)
        val m = r.addMapping(pkg, BodyMappingSpec("*/proto/order", MappingPart.REQUEST, "fixture", "fixture.v1.Order"))
        val broken = r.removeSchema(pkg, "fixture")
        assertEquals(listOf(m.id), broken.map { it.id })
        assertTrue(r.mappings(pkg).single { it.id == m.id }.broken!!.contains("fixture"))
        val decoded = BodyDecoding(r.rules(pkg)).decode(http("http://h/proto/order"), FixtureWire.order(), info(FixtureWire.order()))
        assertEquals(DecodingOutcome.NOT_DECODED, decoded.info.outcome)
        assertTrue("fixture" in decoded.info.reason!!, decoded.info.reason)

        r.addSchema(pkg, "fixture", SchemaInput.ProtoSources(mapOf("other.proto" to "syntax = \"proto3\";\npackage fixture.v1;\nmessage Other { int32 x = 1; }\n")))
        assertTrue("fixture.v1.Order" in r.mappings(pkg).single { it.id == m.id }.broken!!, "type gone after replacement")
        r.addSchema(pkg, "fixture", sources)
        assertNull(r.mappings(pkg).single { it.id == m.id }.broken, "usable again")
        assertTrue(r.removeMapping(pkg, m.id))
        assertTrue(!r.removeMapping(pkg, m.id))
    }

    @Test
    fun `apps are isolated`() {
        val r = registry()
        r.addSchema(pkg, "fixture", sources)
        r.addMapping(pkg, BodyMappingSpec("*/proto/order", MappingPart.REQUEST, "fixture", "fixture.v1.Order"))
        assertEquals(emptyList(), r.schemas("com.other"))
        assertNull(r.rules("com.other").firstNotNullOfOrNull { it.select(http("http://h/proto/order")) })
    }
}
