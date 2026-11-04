package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 039: format rules and how one reads a connection, in the terminal. */
class FormatRuleFormatTest {
    private fun format(op: String, json: String) = Formatters(Style.PLAIN).format(op, Json.parseToJsonElement(json))

    @Test
    fun `a rule names what it reads`() {
        val text = format("network.format.list", """[{"id":"f1","name":"orders","urlPattern":"wss://x/live*","direction":"in","pattern":"^(?<id>\\d+)\\|(?<kind>\\w+)","idPath":"h.rid","addedAtMs":1}]""")
        assertTrue(text.startsWith("f1  orders  wss://x/live*  in"), text)
        assertTrue("pattern ^(?<id>\\d+)\\|(?<kind>\\w+)" in text && "id h.rid" in text, text)
        assertEquals("No format rules. Add one with 'dta network format add'.", format("network.format.list", "[]"))
        assertEquals("Removed format rule f1.", format("network.format.remove", """{"removed":"f1"}"""))
    }

    @Test
    fun `a test says how many it read and shows both kinds`() {
        val text = format("network.format.test", """{"read":2,"of":3,"samples":[{"text":"1|A|{}","envelope":{"format":"orders","kind":"A","id":"1","payload":"{}","rule":"test"}}],"unread":["hello"]}""")
        assertTrue(text.startsWith("Reads 2 of 3 messages."), text)
        assertTrue("[A #1]  1|A|{}" in text, text)
        assertTrue("not read:" in text && "hello" in text, text)
    }
}
