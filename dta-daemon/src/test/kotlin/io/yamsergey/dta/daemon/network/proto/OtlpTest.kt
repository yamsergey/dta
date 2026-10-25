package io.yamsergey.dta.daemon.network.proto

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingOutcome
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtlpTest {

    private val decoding = BodyDecoding(Otlp.rules)

    private fun target(path: String, method: String = "POST", part: MappingPart = MappingPart.REQUEST, contentType: String? = "application/x-protobuf") =
        BodyTarget("http://collector:4318$path", method, part, null, contentType)

    private fun info(bytes: ByteArray, encoding: String? = null) =
        BodyInfo(BodyPart.REQUEST, bytes.size.toLong(), contentType = "application/x-protobuf", encoding = encoding, binary = true, completeness = Completeness.COMPLETE)

    private fun decode(path: String, bytes: ByteArray, encoding: String? = null) = decoding.decode(target(path), bytes, info(bytes, encoding))

    private fun assertJson(expected: String, actual: String?) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual!!), actual)

    @Test
    fun `recognizes OTLP HTTP protobuf exports by method, path and content type`() {
        for ((path, kind) in listOf("/v1/traces" to "traces", "/v1/metrics" to "metrics", "/v1/logs" to "logs")) {
            val selected = Otlp.rules.firstNotNullOfOrNull { it.select(target(path)) }!!
            assertEquals("builtin:otlp-$kind", selected.rule)
            assertEquals("otlp-$kind", selected.format)
            assertEquals("otlp", selected.schema)
        }
        assertEquals(
            "opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest",
            Otlp.rules.firstNotNullOfOrNull { it.select(target("/otel/v1/traces?x=1", contentType = "application/protobuf; charset=binary")) }!!.type,
        )
        assertEquals(
            "opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse",
            Otlp.rules.firstNotNullOfOrNull { it.select(target("/v1/traces", part = MappingPart.RESPONSE)) }!!.type,
        )
        for (miss in listOf(target("/v1/traces", method = "GET"), target("/v1/traces", contentType = "application/json"), target("/v1/spans"), target("/v1/traces", contentType = null))) {
            assertNull(Otlp.rules.firstNotNullOfOrNull { it.select(miss) }, "$miss")
        }
    }

    @Test
    fun `traces decode to official OTLP JSON`() {
        val result = decode("/v1/traces", OtlpVectors.traces())
        assertEquals(DecodingOutcome.DECODED, result.info.outcome, result.info.warnings.toString())
        assertEquals("opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest", result.info.type)
        assertJson(OtlpVectors.TRACES_JSON, result.json)
    }

    @Test
    fun `metrics decode to official OTLP JSON with hex exemplar ids`() {
        val result = decode("/v1/metrics", OtlpVectors.metrics())
        assertEquals(DecodingOutcome.DECODED, result.info.outcome, result.info.warnings.toString())
        assertJson(OtlpVectors.METRICS_JSON, result.json)
    }

    @Test
    fun `logs decode to official OTLP JSON`() {
        val result = decode("/v1/logs", OtlpVectors.logs())
        assertEquals(DecodingOutcome.DECODED, result.info.outcome, result.info.warnings.toString())
        assertJson(OtlpVectors.LOGS_JSON, result.json)
    }

    @Test
    fun `gzip exports decode the same`() {
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(OtlpVectors.traces()) } }.toByteArray()
        assertEquals(decode("/v1/traces", OtlpVectors.traces()), decode("/v1/traces", zipped, "gzip"))
    }

    @Test
    fun `implausible ids are warnings with their paths`() {
        val result = decode("/v1/traces", OtlpVectors.traces(OtlpVectors.span(traceId = ByteArray(15) { 1 }, spanId = ByteArray(7) { 2 })))
        assertEquals(DecodingOutcome.DECODED_WITH_WARNINGS, result.info.outcome)
        assertEquals(
            listOf(
                "traceId at $.resourceSpans[0].scopeSpans[0].spans[0] is 15 bytes, expected 16",
                "spanId at $.resourceSpans[0].scopeSpans[0].spans[0] is 7 bytes, expected 8",
            ),
            result.info.warnings,
        )
    }

    @Test
    fun `an export with no resource entries is a warning`() {
        val result = decode("/v1/logs", ByteArray(0))
        assertEquals(DecodingOutcome.DECODED_WITH_WARNINGS, result.info.outcome)
        assertEquals(listOf("export has no resourceLogs"), result.info.warnings)
    }

    @Test
    fun `an empty export response decodes cleanly`() {
        val result = decoding.decode(target("/v1/traces", part = MappingPart.RESPONSE), ByteArray(0), info(ByteArray(0)))
        assertEquals(DecodingOutcome.DECODED, result.info.outcome)
        assertEquals("{}", result.json)
    }

    @Test
    fun `metrics bytes sent to the traces endpoint are never a clean decode`() {
        val result = decode("/v1/traces", OtlpVectors.metrics())
        assertNotEquals(DecodingOutcome.DECODED, result.info.outcome)
        assertTrue(result.info.warnings.isNotEmpty() || result.info.reason != null)
    }
}
