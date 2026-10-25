package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.logs.v1.SeverityNumber
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.Exemplar
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.metrics.v1.Sum
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status

/**
 * OTLP exports built with the generated classes, and their OTLP/JSON written by hand from
 * the OTLP specification (hex ids, enums as integers, 64-bit integers as strings).
 */
object OtlpVectors {

    val TRACE_ID: ByteArray = ByteArray(16) { (it + 1).toByte() }
    val SPAN_ID: ByteArray = ByteArray(8) { (0xA1 + it).toByte() }
    val PARENT_ID: ByteArray = ByteArray(8) { (0xB1 + it).toByte() }
    const val TRACE_HEX = "0102030405060708090a0b0c0d0e0f10"
    const val SPAN_HEX = "a1a2a3a4a5a6a7a8"
    const val PARENT_HEX = "b1b2b3b4b5b6b7b8"

    private fun kv(key: String, value: String) =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    private val resource = Resource.newBuilder().addAttributes(kv("service.name", "svc")).build()
    private val scope = InstrumentationScope.newBuilder().setName("lib").build()

    fun span(traceId: ByteArray = TRACE_ID, spanId: ByteArray = SPAN_ID): Span = Span.newBuilder()
        .setTraceId(ByteString.copyFrom(traceId))
        .setSpanId(ByteString.copyFrom(spanId))
        .setParentSpanId(ByteString.copyFrom(PARENT_ID))
        .setName("checkout")
        .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
        .setStartTimeUnixNano(1_700_000_000_000_000_000)
        .setEndTimeUnixNano(1_700_000_001_000_000_000)
        .addAttributes(kv("order.id", "ord-42"))
        .addAttributes(KeyValue.newBuilder().setKey("n").setValue(AnyValue.newBuilder().setIntValue(7)))
        .addLinks(Span.Link.newBuilder().setTraceId(ByteString.copyFrom(TRACE_ID)).setSpanId(ByteString.copyFrom(PARENT_ID)))
        .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))
        .build()

    fun traces(span: Span = span()): ByteArray = ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder().setResource(resource).addScopeSpans(ScopeSpans.newBuilder().setScope(scope).addSpans(span)))
        .build().toByteArray()

    val TRACES_JSON = """
        {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"svc"}}]},
         "scopeSpans":[{"scope":{"name":"lib"},"spans":[{
           "traceId":"$TRACE_HEX","spanId":"$SPAN_HEX","parentSpanId":"$PARENT_HEX","name":"checkout","kind":3,
           "startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000001000000000",
           "attributes":[{"key":"order.id","value":{"stringValue":"ord-42"}},{"key":"n","value":{"intValue":"7"}}],
           "links":[{"traceId":"$TRACE_HEX","spanId":"$PARENT_HEX"}],
           "status":{"message":"boom","code":2}}]}]}]}
    """

    fun metrics(): ByteArray = ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(
            ResourceMetrics.newBuilder().setResource(resource).addScopeMetrics(
                ScopeMetrics.newBuilder().setScope(scope).addMetrics(
                    Metric.newBuilder().setName("orders.count").setSum(
                        Sum.newBuilder()
                            .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                            .setIsMonotonic(true)
                            .addDataPoints(
                                NumberDataPoint.newBuilder().setTimeUnixNano(1_700_000_000_000_000_000).setAsInt(3)
                                    .addExemplars(
                                        Exemplar.newBuilder().setAsInt(3)
                                            .setTraceId(ByteString.copyFrom(TRACE_ID)).setSpanId(ByteString.copyFrom(SPAN_ID)),
                                    ),
                            ),
                    ),
                ),
            ),
        )
        .build().toByteArray()

    val METRICS_JSON = """
        {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"svc"}}]},
         "scopeMetrics":[{"scope":{"name":"lib"},"metrics":[{"name":"orders.count","sum":{
           "dataPoints":[{"timeUnixNano":"1700000000000000000","asInt":"3",
             "exemplars":[{"asInt":"3","spanId":"$SPAN_HEX","traceId":"$TRACE_HEX"}]}],
           "aggregationTemporality":2,"isMonotonic":true}}]}]}]}
    """

    fun logs(): ByteArray = ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(
            ResourceLogs.newBuilder().setResource(resource).addScopeLogs(
                ScopeLogs.newBuilder().setScope(scope).addLogRecords(
                    LogRecord.newBuilder()
                        .setTimeUnixNano(1_700_000_000_000_000_000)
                        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                        .setSeverityText("INFO")
                        .setBody(AnyValue.newBuilder().setStringValue("order placed ord-42"))
                        .addAttributes(kv("auth.password", "hunter2"))
                        .setTraceId(ByteString.copyFrom(TRACE_ID))
                        .setSpanId(ByteString.copyFrom(SPAN_ID)),
                ),
            ),
        )
        .build().toByteArray()

    val LOGS_JSON = """
        {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"svc"}}]},
         "scopeLogs":[{"scope":{"name":"lib"},"logRecords":[{
           "timeUnixNano":"1700000000000000000","severityNumber":9,"severityText":"INFO",
           "body":{"stringValue":"order placed ord-42"},
           "attributes":[{"key":"auth.password","value":{"stringValue":"hunter2"}}],
           "traceId":"$TRACE_HEX","spanId":"$SPAN_HEX"}]}]}]}
    """
}
