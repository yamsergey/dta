package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.Descriptors
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingOutcome
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoSourceCompilerTest {

    /** The fixture schema shipped with the harness (copied into test resources). */
    private val fixture: Map<String, String> = run {
        val root = File(checkNotNull(javaClass.getResource("/proto/fixture.proto")).toURI()).parentFile
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".proto") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    private fun types(files: List<Descriptors.FileDescriptor>) = files.flatMap { f -> f.messageTypes.flatMap { listOf(it) + it.nestedTypes } }.associateBy { it.fullName }

    @Test
    fun `the fixture schema compiles with nested enums, oneofs, maps and proto3 optional`() {
        val order = types(ProtoSourceCompiler.compile(fixture)).getValue("fixture.v1.Order")
        assertEquals(listOf("id", "amount_cents", "status", "items", "tags", "card", "voucher", "meta", "note"), order.fields.map { it.name })
        assertEquals(Descriptors.FieldDescriptor.Type.ENUM, order.findFieldByName("status").type)
        assertEquals("fixture.v1.Order.Status", order.findFieldByName("status").enumType.fullName)
        assertTrue(order.findFieldByName("items").isRepeated)
        assertEquals("fixture.v1.Item", order.findFieldByName("items").messageType.fullName)
        assertTrue(order.findFieldByName("tags").isMapField, "map<string,string> synthesizes a map entry")
        assertEquals("payment", order.findFieldByName("card").realContainingOneof!!.name)
        assertEquals("payment", order.findFieldByName("voucher").realContainingOneof!!.name)
        assertNull(order.findFieldByName("note").realContainingOneof)
        assertTrue(order.findFieldByName("note").hasPresence(), "proto3 optional keeps presence")
        assertEquals("fixture.v1.Common", order.findFieldByName("meta").messageType.fullName)
        assertEquals(4, order.findFieldByName("items").number)
    }

    @Test
    fun `bytes encoded by the fixture decode with names`() {
        val order = types(ProtoSourceCompiler.compile(fixture)).getValue("fixture.v1.Order")
        val result = ProtoDecoder.decode(FixtureWire.order(), Completeness.COMPLETE, Selection(order, "protobuf", "fixture", "m1"))
        assertEquals(DecodingOutcome.DECODED, result.info.outcome, result.info.warnings.toString())
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"ord-77","amountCents":"1999","status":"STATUS_PAID","items":[{"sku":"sku-1","quantity":2}],
                   "tags":{"channel":"app"},"card":{"number":"4111111111111111","holder":"A. Tester"},
                   "meta":{"client":"fixture","version":3}}""",
            ),
            Json.parseToJsonElement(result.json!!),
        )
    }

    @Test
    fun `a missing import is named`() {
        val e = assertFailsWith<SchemaException> { ProtoSourceCompiler.compile(mapOf("fixture.proto" to fixture.getValue("fixture.proto"))) }
        assertTrue("fixture/v1/common.proto" in e.message!! && "fixture.proto" in e.message!!, e.message)
    }

    @Test
    fun `a syntax error names file, line and column`() {
        val e = assertFailsWith<SchemaException> {
            ProtoSourceCompiler.compile(mapOf("bad.proto" to "syntax = \"proto3\";\nmessage A {\n  int32 x = ;\n}\n"))
        }
        assertTrue("bad.proto:3:" in e.message!!, e.message)
    }

    @Test
    fun `an unknown type names the field`() {
        val e = assertFailsWith<SchemaException> {
            ProtoSourceCompiler.compile(mapOf("t.proto" to "syntax = \"proto3\";\npackage p;\nmessage A {\n  Missing m = 1;\n}\n"))
        }
        assertTrue("Missing" in e.message!! && "t.proto" in e.message!!, e.message)
    }

    @Test
    fun `extend and groups are rejected with the construct and location`() {
        val extend = assertFailsWith<SchemaException> {
            ProtoSourceCompiler.compile(mapOf("ext.proto" to "syntax = \"proto2\";\nmessage A {\n  extensions 100 to 200;\n}\nextend A {\n  optional int32 x = 100;\n}\n"))
        }
        assertTrue("extend" in extend.message!! && "ext.proto:5:" in extend.message!!, extend.message)
        val group = assertFailsWith<SchemaException> {
            ProtoSourceCompiler.compile(mapOf("g.proto" to "syntax = \"proto2\";\nmessage A {\n  optional group G = 1 {\n    optional int32 v = 2;\n  }\n}\n"))
        }
        assertTrue("group" in group.message!! && "g.proto:3:" in group.message!!, group.message)
    }

    @Test
    fun `proto2 required fields keep their label`() {
        val files = ProtoSourceCompiler.compile(mapOf("p2.proto" to "syntax = \"proto2\";\npackage p;\nmessage A {\n  required string id = 1;\n  optional int32 n = 2 [default = 5];\n}\n"))
        val a = types(files).getValue("p.A")
        assertEquals(FieldDescriptorProto.Label.LABEL_REQUIRED, a.findFieldByName("id").toProto().label)
    }

    @Test
    fun `well-known imports resolve to the bundled descriptors`() {
        val files = ProtoSourceCompiler.compile(
            mapOf("w.proto" to "syntax = \"proto3\";\npackage p;\nimport \"google/protobuf/timestamp.proto\";\nmessage A {\n  google.protobuf.Timestamp at = 1;\n}\n"),
        )
        assertEquals("google.protobuf.Timestamp", types(files).getValue("p.A").findFieldByName("at").messageType.fullName)
    }

    @Test
    fun `nested and relative type references resolve by protobuf scoping`() {
        val files = ProtoSourceCompiler.compile(
            mapOf(
                "n.proto" to """
                    syntax = "proto3";
                    package p.q;
                    message Outer {
                      message Inner { int32 v = 1; }
                      Inner inner = 1;
                    }
                    message Other {
                      Outer.Inner a = 1;
                      .p.q.Outer b = 2;
                      q.Outer c = 3;
                    }
                """.trimIndent(),
            ),
        )
        val other = types(files).getValue("p.q.Other")
        assertEquals("p.q.Outer.Inner", other.findFieldByName("a").messageType.fullName)
        assertEquals("p.q.Outer", other.findFieldByName("b").messageType.fullName)
        assertEquals("p.q.Outer", other.findFieldByName("c").messageType.fullName)
        assertNotNull(types(files)["p.q.Outer.Inner"])
    }
}

/** The fixture app's hand encoding (FX/ProtoWriter.kt), reproduced as a test vector. */
object FixtureWire {
    private class W {
        val out = ByteArrayOutputStream()
        fun varint(field: Int, v: Long) = apply { raw(((field shl 3) or 0).toLong()); raw(v) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun bytes(field: Int, b: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(b.size.toLong()); out.write(b) }
        fun message(field: Int, w: W) = bytes(field, w.out.toByteArray())
        private fun raw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
    }

    fun order(): ByteArray = W()
        .string(1, "ord-77").varint(2, 1999).varint(3, 2)
        .message(4, W().string(1, "sku-1").varint(2, 2))
        .message(5, W().string(1, "channel").string(2, "app"))
        .message(6, W().string(1, "4111111111111111").string(2, "A. Tester"))
        .message(8, W().string(1, "fixture").varint(2, 3))
        .out.toByteArray()

    fun chatMessage(): ByteArray = W().string(1, "fixture").string(2, "hello-proto").varint(3, 1_700_000_000_000).out.toByteArray()
}
