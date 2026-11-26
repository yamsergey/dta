package io.yamsergey.dta.cli.format

import io.yamsergey.dta.protocol.network.MaskedLocation
import io.yamsergey.dta.protocol.network.RedactionInfo
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.timeline.TimelinePage
import kotlin.test.Test
import kotlin.test.assertTrue

/** 072 FR-004: a text answer to an agent states redaction, as the contract's Statements say. */
class RedactionStatementFormatTest {
    private val f = Formatters(Style.PLAIN)
    private val on = RedactionInfo(forAgents = true, masked = listOf(MaskedLocation("event 7:6 title", "4eb0314abc32", 14)))
    private val off = RedactionInfo(applied = false, forAgents = true)

    private fun events(info: RedactionInfo?) =
        f.format("timeline.events", DtaJson.encodeToJsonElement(TimelinePage.serializer(), TimelinePage(emptyList(), "c:9", redaction = info)))

    @Test
    fun `D-105 timeline events text states redacted for agents`() {
        val text = events(on)
        assertTrue("redacted for agents: 1 values masked (fingerprints: search redacted:#…)" in text, text)
    }

    @Test
    fun `D-105 timeline events text states that redaction is off`() {
        val text = events(off)
        assertTrue("not redacted: redaction for agents is off in the plugin's settings" in text, text)
    }

    @Test
    fun `D-105 a page without redaction (the plugin's) states nothing`() {
        assertTrue("redacted" !in events(null), events(null))
    }

    private val statement = "redacted for agents: 1 values masked (fingerprints: search redacted:#…)"

    @Test
    fun `D-109 an export's text states what was masked in the file`() {
        val result = DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.NetworkExportResult.serializer(),
            io.yamsergey.dta.protocol.network.NetworkExportResult("/tmp/x.har", 2, 0, 1, 0, redaction = on))
        val text = f.format("network.export", result)
        assertTrue(statement in text, text)
    }

    @Test
    fun `D-109 a streamed search's end states redaction`() {
        val page = DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.SearchPage.serializer(),
            io.yamsergey.dta.protocol.network.SearchPage(emptyList(), redaction = on)) as kotlinx.serialization.json.JsonObject
        val text = f.findEnd(page, 1)
        assertTrue(statement in text, text)
        val offText = f.findEnd(DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.SearchPage.serializer(),
            io.yamsergey.dta.protocol.network.SearchPage(emptyList(), redaction = off)) as kotlinx.serialization.json.JsonObject, 1)
        assertTrue("not redacted: redaction for agents is off in the plugin's settings" in offText, offText)
    }
}
