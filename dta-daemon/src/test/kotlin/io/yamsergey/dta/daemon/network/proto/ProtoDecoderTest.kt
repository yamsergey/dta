package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoDecoderTest {

    private fun field(name: String, number: Int, type: FieldDescriptorProto.Type, typeName: String? = null, repeated: Boolean = false) =
        FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
            .setLabel(if (repeated) FieldDescriptorProto.Label.LABEL_REPEATED else FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .apply { typeName?.let { setTypeName(it) } }
            .build()

    private val file: Descriptors.FileDescriptor = Descriptors.FileDescriptor.buildFrom(
        FileDescriptorProto.newBuilder().setName("test.proto").setPackage("t").setSyntax("proto3")
            .addMessageType(
                DescriptorProto.newBuilder().setName("Item")
                    .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(field("qty", 2, FieldDescriptorProto.Type.TYPE_INT32)),
            )
            .addMessageType(
                DescriptorProto.newBuilder().setName("Order")
                    .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(field("amount_cents", 2, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(field("items", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, ".t.Item", repeated = true)),
            )
            .build(),
        arrayOf(),
    )
    private val order = file.findMessageTypeByName("Order")
    private val item = file.findMessageTypeByName("Item")
    private val selection = Selection(order, format = "protobuf", schema = "test", rule = "m1")

    private fun item(sku: String, extra: ByteArray = ByteArray(0)) =
        DynamicMessage.newBuilder(item).setField(item.findFieldByName("sku"), sku).build().toByteArray() + extra

    /** Field 99, varint 1: tag (99 << 3 | 0) = 792 = 0x98 0x06. */
    private val unknown99 = byteArrayOf(0x98.toByte(), 0x06, 0x01)

    private fun order(itemBytes: ByteArray): ByteArray =
        DynamicMessage.newBuilder(order)
            .setField(order.findFieldByName("order_id"), "ord-1")
            .setField(order.findFieldByName("amount_cents"), 1999L)
            .build().toByteArray() + byteArrayOf(0x1A, itemBytes.size.toByte()) + itemBytes

    @Test
    fun `a valid body decodes to proto3 JSON`() {
        val result = ProtoDecoder.decode(order(item("sku-1")), Completeness.COMPLETE, selection)
        assertEquals(DecodingOutcome.DECODED, result.info.outcome)
        assertEquals("t.Order", result.info.type)
        assertEquals("test", result.info.schema)
        assertEquals("m1", result.info.rule)
        assertEquals("protobuf", result.info.format)
        val json = Json.parseToJsonElement(result.json!!).jsonObject
        assertEquals("ord-1", json["orderId"]!!.jsonPrimitive.content)
        assertTrue(json["amountCents"]!!.jsonPrimitive.isString, "int64 printed as a string")
        assertEquals("sku-1", json["items"]!!.jsonArray[0].jsonObject["sku"]!!.jsonPrimitive.content)
    }

    @Test
    fun `garbage is not decoded and says why`() {
        val result = ProtoDecoder.decode(byteArrayOf(0x0A, 0x7F, 0x41, 0x41), Completeness.COMPLETE, selection)
        assertEquals(DecodingOutcome.NOT_DECODED, result.info.outcome)
        assertNull(result.json)
        assertTrue("t.Order" in result.info.reason!!, result.info.reason)
    }

    @Test
    fun `unknown fields are warnings with their paths, top level and nested`() {
        val result = ProtoDecoder.decode(order(item("sku-1", unknown99)) + unknown99, Completeness.COMPLETE, selection)
        assertEquals(DecodingOutcome.DECODED_WITH_WARNINGS, result.info.outcome)
        assertEquals(listOf("unknown field 99 at $", "unknown field 99 at $.items[0]"), result.info.warnings.sorted())
        assertEquals("ord-1", Json.parseToJsonElement(result.json!!).jsonObject["orderId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an incomplete body is never decoded`() {
        var called = false
        val guarded = selection.copy(postProcess = { called = true; it to emptyList() })
        val result = ProtoDecoder.decode(order(item("sku-1")), Completeness.TRUNCATED, guarded)
        assertEquals(DecodingOutcome.NOT_DECODED, result.info.outcome)
        assertEquals("body incomplete (truncated)", result.info.reason)
        assertTrue(!called)
    }

    @Test
    fun `an empty body is an empty message`() {
        val result = ProtoDecoder.decode(ByteArray(0), Completeness.COMPLETE, selection)
        assertEquals(DecodingOutcome.DECODED, result.info.outcome)
        assertEquals("{}", result.json)
    }

    @Test
    fun `a broken rule is not decoded with its reason`() {
        val broken = Selection(null, format = "protobuf", schema = "gone", rule = "m2", type = "x.Y", broken = "schema gone was removed")
        val result = ProtoDecoder.decode(ByteArray(3), Completeness.COMPLETE, broken)
        assertEquals(DecodingOutcome.NOT_DECODED, result.info.outcome)
        assertEquals("schema gone was removed", result.info.reason)
        assertEquals("x.Y", result.info.type)
    }

    @Test
    fun `post-processing rewrites JSON and adds warnings`() {
        val tagged = selection.copy(postProcess = { json -> json to listOf("implausible") })
        val result = ProtoDecoder.decode(order(item("a")), Completeness.COMPLETE, tagged)
        assertEquals(DecodingOutcome.DECODED_WITH_WARNINGS, result.info.outcome)
        assertEquals(listOf("implausible"), result.info.warnings)
    }
}
