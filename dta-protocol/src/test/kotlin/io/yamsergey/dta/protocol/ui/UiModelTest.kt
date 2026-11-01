package io.yamsergey.dta.protocol.ui

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 015 (T001): the journal's event shape, shared by the sidekick, daemon and clients. */
class UiModelTest {
    private val dialog = WindowRef(windowId = "w3", kind = WindowKind.DIALOG, title = "Delete item?", owner = "com.example.MainActivity", type = 2)

    private val appeared = UiEvent(
        id = "1793000000000:7",
        seq = 7,
        monoMs = 12_345,
        wallMs = 1_793_000_012_345,
        kind = UiEventKind.WINDOW_APPEARED,
        source = UiSource.WINDOW,
        window = dialog,
        content = ContentSummary(
            elements = listOf(
                SummaryElement(role = "heading", text = "Delete item?", depth = 2),
                SummaryElement(role = "button", text = "Delete", name = "android:id/button1", depth = 3),
                SummaryElement(role = "input", password = true, depth = 3),
            ),
            omitted = 4,
            settled = false,
            takenMonoMs = 12_345,
        ),
        completeness = listOf(DegradationMarker("ui", "${DegradationCodes.UI_NOT_SETTLED}: removed before it settled")),
        links = listOf(EventLink(kind = "network", id = "n42", relation = LinkRelation.IN_FLIGHT)),
    )

    @Test
    fun `every kind round-trips with kebab-case names`() {
        for (kind in UiEventKind.entries) {
            val e = appeared.copy(kind = kind)
            val json = DtaJson.encodeToString(UiEvent.serializer(), e)
            assertEquals(e, DtaJson.decodeFromString(UiEvent.serializer(), json))
        }
        val json = DtaJson.encodeToString(UiEvent.serializer(), appeared)
        assertTrue("\"window-appeared\"" in json, json)
        assertTrue("\"in-flight\"" in json, json)
        assertTrue("\"dialog\"" in json, json)
    }

    @Test
    fun `absent fields stay out of the JSON`() {
        val toast = UiEvent(id = "e:1", seq = 1, monoMs = 1, wallMs = 2, kind = UiEventKind.TOAST, source = UiSource.TOAST, text = "Saved", durationMs = 2000)
        val json = DtaJson.encodeToString(UiEvent.serializer(), toast)
        assertFalse("window" in json, json)
        assertFalse("content" in json, json)
        assertEquals(toast, DtaJson.decodeFromString(UiEvent.serializer(), json))
    }

    @Test
    fun `a page carries events, the next cursor and the capability`() {
        val page = UiEventsPage(
            events = listOf(appeared),
            next = "s1:8",
            more = false,
            capability = UiCapability(
                sources = listOf(
                    UiSourceState(UiSource.WINDOW, SourceState.ON),
                    UiSourceState(UiSource.ELEMENT, SourceState.DEGRADED, "backed off"),
                ),
                settleMs = 250, maxWaitMs = 1000, bufferSize = 500, hostBound = 5000,
            ),
        )
        val json = DtaJson.encodeToString(UiEventsPage.serializer(), page)
        assertEquals(page, DtaJson.decodeFromString(UiEventsPage.serializer(), json))
    }

    @Test
    fun `a gap names what was lost`() {
        val gap = UiEvent(
            id = "e:9", seq = 9, monoMs = 1, wallMs = 2, kind = UiEventKind.GAP, source = UiSource.JOURNAL,
            gapCount = 4, completeness = listOf(DegradationMarker("ui", "${DegradationCodes.UI_EVENTS_DROPPED}: 4 events")),
        )
        assertEquals(gap, DtaJson.decodeFromString(UiEvent.serializer(), DtaJson.encodeToString(UiEvent.serializer(), gap)))
    }
}
