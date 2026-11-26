package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D-041 (NET-RED-034): a streamed search gathered into one answer (CLI `--json`, MCP `network_search`)
 * says it is redacted, as the stream's final event does (004 FR-032, 071 contracts/find.md).
 */
class FindCollectorTest {
    private fun collected(vararg events: FoundEvent): JsonObject = FindCollector().apply { events.forEach { take(it) } }.json()

    @Test
    fun `the gathered answer carries the final event's redaction, applied stated`() {
        val page = collected(
            FoundEvent("f1", FindState.SEARCHING),
            FoundEvent("f1", FindState.DONE, redaction = RedactionInfo(masked = listOf(MaskedLocation("entry r1:1 url.query access_token", "0123456789ab", 6)))),
        )
        val redaction = page["redaction"]!!.jsonObject
        assertEquals("true", redaction["applied"]!!.jsonPrimitive.content)
        assertEquals(listOf("entry r1:1 url.query access_token"), redaction["masked"]!!.jsonArray.map { it.jsonObject["place"]!!.jsonPrimitive.content })
        assertEquals("done", page["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `twin - without redaction the answer states none`() {
        val page = collected(FoundEvent("f1", FindState.DONE))
        assertNull(page["redaction"])
    }

    @Test
    fun `a redaction with nothing masked still says it was applied`() {
        val json = DtaJson.encodeToJsonElement(RedactionInfo.serializer(), RedactionInfo()).jsonObject
        assertEquals("true", json["applied"]!!.jsonPrimitive.content)
    }
}
