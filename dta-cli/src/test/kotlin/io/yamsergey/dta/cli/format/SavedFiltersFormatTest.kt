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
}
