package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** NET-EXP-014 (071 FR-004, contract "Every answer: scope"): the export's text says what it covered. */
class ExportScopeFormatTest {
    private fun format(json: String) = Formatters(Style.PLAIN).format("network.export", Json.parseToJsonElement(json))
    private val base = """"file":"/tmp/a.har","entries":7,"webSocketMessages":4,"bodies":11,"bodiesOmitted":0"""

    @Test
    fun `the current run with earlier history kept says the archive holds it and how to include it`() {
        val text = format("""{$base,"scope":{"archive":false,"entries":7,"run":{"runId":"r1","entries":7},"outside":5}}""")
        assertContains(text, "Exported 7 entries")
        assertContains(text, "7 entries in this run · earlier runs are in the archive (--archive includes them)")
    }

    @Test
    fun `twin - the whole history names no earlier runs`() {
        val text = format("""{$base,"scope":{"archive":true,"entries":12}}""")
        assertContains(text, "12 entries in the history")
        assertFalse("earlier runs" in text, text)
    }
}
