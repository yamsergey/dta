package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.ops.Selected
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** D-113, 072 FR-004: an MCP answer to an agent carries the redaction statement as text (in `redaction.statement`), and is still one JSON. */
class McpRedactionStatementTest {
    private fun answer(redaction: String) = McpTools(call = { _, _ ->
        Selected(null, Json.parseToJsonElement("""{"entry":{"id":"r1:1"},"redaction":$redaction}""") as JsonObject)
    }).let { runBlocking { it.invoke("network_entry", Json.parseToJsonElement("""{"id":"r1:1"}""") as JsonObject).text } }

    @Test
    fun `masked values are stated`() {
        val text = answer("""{"applied":true,"forAgents":true,"masked":[{"place":"p","fingerprint":"3f9a2c81d04e","length":5}]}""")
        Json.parseToJsonElement(text)
        assertTrue("redacted for agents: 1 values masked (fingerprints: search redacted:#…)" in text, text)
    }

    @Test
    fun `the setting being off is stated`() {
        val text = answer("""{"applied":false,"forAgents":true}""")
        assertTrue("not redacted: redaction for agents is off in the plugin's settings" in text, text)
    }
}
