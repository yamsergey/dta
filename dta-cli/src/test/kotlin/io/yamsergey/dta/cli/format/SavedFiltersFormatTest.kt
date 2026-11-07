package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SavedFiltersFormatTest {
    private fun format(op: String, json: String) = Formatters(Style.PLAIN).format(op, Json.parseToJsonElement(json))
    private val base = """"entries":[],"total":0,"coverage":{"complete":true}"""

    @Test
    fun `a list says what saved filters hid`() {
        val text = format("network.list", """{$base,"saved":{"words":["-host:t.example.test","status:2xx"],"hidden":26}}""")
        assertContains(text, "hidden by saved filters: 26 (-host:t.example.test, status:2xx)")
    }

    @Test
    fun `a list that ignored them says so`() {
        val text = format("network.list", """{$base,"saved":{"words":["-url:/a"],"ignored":true}}""")
        assertContains(text, "saved filters ignored: -url:/a")
    }

    @Test
    fun `the saved set and recent ones`() {
        assertEquals("-url:/a\nrecent: status:2xx", format("network.filters.list", """{"saved":["-url:/a"],"recent":[{"word":"status:2xx","lastUsedMs":1}]}"""))
        assertEquals("No saved filters. Save one with 'dta network filters add'.", format("network.filters.list", "{}"))
    }

    @Test
    fun `flows read as what happened`() {
        val flow = """{"id":"f2","name":"Buy ACME","startMs":5}"""
        assertEquals("Started f2 Buy ACME. Rows are in it by time only.", format("timeline.flow.start", """{"flow":$flow}"""))
        assertEquals("Started f2 Buy ACME. Rows are in it by time only.\nEnded f1 Login.", format("timeline.flow.start", """{"flow":$flow,"ended":{"id":"f1","name":"Login","startMs":1,"endMs":5}}"""))
        assertEquals("Ended f2 Buy ACME.", format("timeline.flow.end", flow))
        assertEquals("f2 is now Buy ACME.", format("timeline.flow.rename", flow))
        assertEquals("Removed f2. Nothing recorded is deleted.", format("timeline.flow.remove", """{"removed":"f2"}"""))
        assertEquals("No flows. Start one with 'dta timeline flow start'.", format("timeline.flow.list", """{"flows":[]}"""))
        assertEquals(
            "f1  Login  ended  3 rows  older rows no longer kept\nf2  Buy ACME  recording  1 row",
            format("timeline.flow.list", """{"flows":[{"flow":{"id":"f1","name":"Login","startMs":1,"endMs":5},"rows":3,"rowsGone":true},{"flow":$flow,"rows":1}]}"""),
        )
    }
}
