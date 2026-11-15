package io.yamsergey.dta.cli.format

import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.timeline.TimelinePage
import kotlin.test.Test
import kotlin.test.assertTrue

/** Feature 065: `dta timeline events` says what Network's saved filters hid. */
class TimelineSavedFormatTest {
    private val f = Formatters(Style.PLAIN)

    @Test
    fun `an events page ends with the notice before the cursor`() {
        val page = TimelinePage(emptyList(), "c:9", saved = SavedFiltersApplied(listOf("status:5xx"), hidden = 4))
        val out = f.format("timeline.events", DtaJson.encodeToJsonElement(TimelinePage.serializer(), page)).lines()
        assertTrue("4 network rows hidden by Network's filters (status:5xx)" in out[out.size - 2], out.toString())
        assertTrue(out.last().startsWith("next: c:9"))
    }

    @Test
    fun `a watch line says what the filters hid from the last events`() {
        val data = kotlinx.serialization.json.buildJsonObject {
            put("saved", DtaJson.encodeToJsonElement(SavedFiltersApplied.serializer(), SavedFiltersApplied(listOf("-host:localhost"), hidden = 2)))
        }
        val line = f.timelineEventLine(data)
        assertTrue("2 network rows hidden by Network's filters (-host:localhost)" in line, line)
        assertTrue("--no-saved-filters shows them" in line, line)
    }
}
