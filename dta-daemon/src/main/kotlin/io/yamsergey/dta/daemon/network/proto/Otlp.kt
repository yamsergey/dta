package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.Descriptors
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.Base64

/**
 * Built-in OTLP/HTTP protobuf decoding (R3): the published OTLP descriptors as a schema
 * set named `otlp`, selected by POST to `…/v1/{traces,metrics,logs}` with a protobuf
 * content type, rendered as the official OTLP/JSON — trace and span ids in hex, enums as
 * integers — with plausibility warnings for ids of the wrong length and empty exports.
 */
object Otlp {

    const val SCHEMA = "otlp"

    private class Kind(
        val name: String,
        val request: Descriptors.Descriptor,
        val response: Descriptors.Descriptor,
        /** The request's top-level repeated field, e.g. `resourceSpans`. */
        val resources: String,
    ) {
        val rule = "builtin:otlp-$name"
        val format = "otlp-$name"
    }

    private val KINDS = listOf(
        Kind("traces", ExportTraceServiceRequest.getDescriptor(), ExportTraceServiceResponse.getDescriptor(), "resourceSpans"),
        Kind("metrics", ExportMetricsServiceRequest.getDescriptor(), ExportMetricsServiceResponse.getDescriptor(), "resourceMetrics"),
        Kind("logs", ExportLogsServiceRequest.getDescriptor(), ExportLogsServiceResponse.getDescriptor(), "resourceLogs"),
    )

    /** Every bytes field OTLP/JSON renders in hex, with its expected length. */
    private val IDS = listOf("traceId" to 16, "spanId" to 8, "parentSpanId" to 8)

    /** One rule per signal, for requests and their responses. */
    val rules: List<DecodingRule> = KINDS.map { kind ->
        DecodingRule { target ->
            if (!target.method.equals("POST", ignoreCase = true) || !isProtobuf(target.contentType)) return@DecodingRule null
            if (!path(target.url).endsWith("/v1/${kind.name}")) return@DecodingRule null
            when (target.part) {
                MappingPart.REQUEST -> Selection(kind.request, kind.format, SCHEMA, kind.rule, enumsAsInts = true, postProcess = { json ->
                    val warnings = mutableListOf<String>()
                    val rewritten = hexIds(json, "$", warnings)
                    if ((json as? JsonObject)?.get(kind.resources) == null) warnings += "export has no ${kind.resources}"
                    rewritten to warnings
                })
                MappingPart.RESPONSE -> Selection(kind.response, kind.format, SCHEMA, kind.rule, enumsAsInts = true, postProcess = { json ->
                    val warnings = mutableListOf<String>()
                    hexIds(json, "$", warnings) to warnings
                })
                MappingPart.MESSAGE -> null
            }
        }
    }

    /** Built-in rules as listed next to user mappings: (rule id, pattern, part, type). */
    val listing: List<BuiltinRule> = KINDS.flatMap { kind ->
        listOf(
            BuiltinRule(kind.rule, "POST */v1/${kind.name}", MappingPart.REQUEST, kind.request.fullName),
            BuiltinRule(kind.rule, "POST */v1/${kind.name}", MappingPart.RESPONSE, kind.response.fullName),
        )
    }

    data class BuiltinRule(val id: String, val pattern: String, val part: MappingPart, val type: String)

    fun isProtobuf(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase() in setOf("application/x-protobuf", "application/protobuf")

    private fun path(url: String): String =
        runCatching { URI(url).rawPath }.getOrNull() ?: url.substringBefore('?').substringBefore('#')

    /** Base64 (proto3 JSON) → hex (OTLP/JSON) for the id fields wherever they occur. */
    private fun hexIds(element: JsonElement, path: String, warnings: MutableList<String>): JsonElement = when (element) {
        is JsonObject -> {
            val ids = IDS.mapNotNull { (name, expected) ->
                val value = (element[name] as? JsonPrimitive)?.takeIf { it.isString } ?: return@mapNotNull null
                val bytes = Base64.getDecoder().decode(value.content)
                if (bytes.size != expected) warnings += "$name at $path is ${bytes.size} bytes, expected $expected"
                name to JsonPrimitive(bytes.joinToString("") { "%02x".format(it) })
            }.toMap()
            JsonObject(element.mapValues { (key, value) -> ids[key] ?: hexIds(value, "$path.$key", warnings) })
        }
        is JsonArray -> JsonArray(element.mapIndexed { i, value -> hexIds(value, "$path[$i]", warnings) })
        else -> element
    }
}
