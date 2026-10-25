package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecodingInfoTest {

    @Test
    fun `outcomes use their wire names and round-trip`() {
        val info = DecodingInfo(
            outcome = DecodingOutcome.DECODED_WITH_WARNINGS, format = "otlp-traces", schema = "otlp",
            type = "opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest", rule = "builtin:otlp-traces",
            warnings = listOf("unknown field 99 at $"),
        )
        val json = DtaJson.encodeToString(DecodingInfo.serializer(), info)
        assertTrue("\"outcome\":\"decoded-with-warnings\"" in json, json)
        assertEquals(info, DtaJson.decodeFromString(DecodingInfo.serializer(), json))
        assertEquals(
            listOf("decoded", "decoded-with-warnings", "not-decoded", "not-applicable"),
            DecodingOutcome.entries.map { DtaJson.encodeToString(DecodingOutcome.serializer(), it).trim('"') },
        )
    }
}
