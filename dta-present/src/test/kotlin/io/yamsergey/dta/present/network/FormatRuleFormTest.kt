package io.yamsergey.dta.present.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatRuleFormTest {
    @Test
    fun `a new rule covers the connection's socket whatever its query`() {
        assertEquals("wss://api.example.test/live*", FormatRuleForm.forConnection("wss://api.example.test/live?token=abc").urlPattern)
    }

    @Test
    fun `only what was typed goes to the daemon`() {
        val p = FormatRuleForm(name = " orders ", urlPattern = "wss://x/*", direction = "in", pattern = "^(?<kind>\\w+) ", idPath = " h.rid ").params()
        assertEquals(mapOf("name" to "orders", "urlPattern" to "wss://x/*", "direction" to "in", "pattern" to "^(?<kind>\\w+) ", "idPath" to "h.rid"), p.mapValues { (it.value as JsonPrimitive).content })
        assertTrue(FormatRuleForm(urlPattern = "x").empty)
    }

    @Test
    fun `an existing rule fills the form`() {
        val f = FormatRuleForm.of(Json.parseToJsonElement("""{"id":"f1","name":"orders","urlPattern":"wss://x/*","kind":"h.op","payloadFormat":"text"}""") as JsonObject)
        assertEquals(listOf("orders", "h.op", "text", "any"), listOf(f.name, f.kind, f.payloadFormat, f.direction))
    }

    @Test
    fun `the preview says how many and shows both kinds`() {
        val text = FormatRuleForm.preview(Json.parseToJsonElement("""{"read":1,"of":2,"samples":[{"text":"1|A|x","envelope":{"format":"o","kind":"A","id":"1","payload":"x","rule":"test","note":"payload is not JSON"}}],"unread":["hello\nworld"]}""") as JsonObject)
        assertTrue(text.startsWith("Reads 1 of 2"), text)
        assertTrue("[A #1]  1|A|x   (payload is not JSON)" in text, text)
        assertTrue("Not read:\n  hello world" in text, text)
    }
}
