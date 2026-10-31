package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingInfo
import io.yamsergey.dta.protocol.network.DecodingOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * What a rule chose for a body: the message type and how to present it. [descriptor] is
 * null when the rule is [broken] (its schema or type is gone).
 */
data class Selection(
    val descriptor: Descriptors.Descriptor?,
    val format: String,
    val schema: String,
    val rule: String,
    val type: String = descriptor?.fullName ?: "",
    val broken: String? = null,
    /** Where the schema came from when a client synced it (feature 013): "project /path, synced …". */
    val origin: String? = null,
    val enumsAsInts: Boolean = false,
    /**
     * Format-specific rewriting of the proto3 JSON plus plausibility warnings (OTLP). Not
     * always an object: well-known types print as strings (Timestamp) or values (wrappers).
     */
    val postProcess: (JsonElement) -> Pair<JsonElement, List<String>> = { it to emptyList() },
)

data class Decoded(val json: String?, val info: DecodingInfo)

/**
 * The one protobuf decoding engine (R2): dynamic message from a descriptor, proto3 JSON.
 * Strict — a parse failure is "not decoded" with the parser's reason, unknown fields
 * (which protobuf keeps instead of failing) are warnings with their paths, and an
 * incomplete body is never decoded.
 */
object ProtoDecoder {

    fun decode(bytes: ByteArray, completeness: Completeness, selection: Selection): Decoded {
        fun info(outcome: DecodingOutcome, warnings: List<String> = emptyList(), reason: String? = null) = DecodingInfo(
            outcome, format = selection.format, schema = selection.schema, type = selection.type,
            rule = selection.rule, warnings = warnings, reason = reason, origin = selection.origin,
        )
        val descriptor = selection.descriptor
            ?: return Decoded(null, info(DecodingOutcome.NOT_DECODED, reason = selection.broken ?: "no message type"))
        if (completeness != Completeness.COMPLETE) {
            return Decoded(null, info(DecodingOutcome.NOT_DECODED, reason = "body incomplete (${completeness.wireName()})"))
        }
        val message = try {
            DynamicMessage.parseFrom(descriptor, bytes)
        } catch (e: InvalidProtocolBufferException) {
            return Decoded(null, info(DecodingOutcome.NOT_DECODED, reason = "does not parse as ${descriptor.fullName}: ${e.message}"))
        }
        val warnings = mutableListOf<String>()
        unknownFields(message, "$", warnings)
        val printed = try {
            JsonFormat.printer().omittingInsignificantWhitespace()
                .let { if (selection.enumsAsInts) it.printingEnumsAsInts() else it }
                .print(message)
        } catch (e: Exception) {
            return Decoded(null, info(DecodingOutcome.NOT_DECODED, warnings, reason = "cannot print ${descriptor.fullName} as JSON: ${e.message}"))
        }
        val (json, extra) = selection.postProcess(Json.parseToJsonElement(printed))
        warnings += extra
        val outcome = if (warnings.isEmpty()) DecodingOutcome.DECODED else DecodingOutcome.DECODED_WITH_WARNINGS
        return Decoded(Json.encodeToString(JsonElement.serializer(), json), info(outcome, warnings))
    }

    /** Unknown fields — including a known number with the wrong wire type — with JSON paths. */
    private fun unknownFields(message: Message, path: String, out: MutableList<String>) {
        message.unknownFields.asMap().keys.forEach { out += "unknown field $it at $path" }
        for ((field, value) in message.allFields) {
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            val at = "$path.${field.jsonName}"
            if (field.isRepeated) (value as List<*>).forEachIndexed { i, v -> unknownFields(v as Message, "$at[$i]", out) }
            else unknownFields(value as Message, at, out)
        }
    }

    private fun Completeness.wireName() = name.lowercase().replace('_', '-')
}
