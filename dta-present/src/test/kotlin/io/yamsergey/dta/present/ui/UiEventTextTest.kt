package io.yamsergey.dta.present.ui

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.EventLink
import io.yamsergey.dta.protocol.ui.LinkRelation
import io.yamsergey.dta.protocol.ui.SummaryElement
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 015: how a UI event reads in the CLI and in the agent's summary line. */
class UiEventTextTest {
    private val dialog = WindowRef("w3", WindowKind.DIALOG, title = "Delete item?")
    private val content = ContentSummary(listOf(
        SummaryElement("heading", "Delete item?"), SummaryElement("text", "This cannot be undone."),
        SummaryElement("button", "Cancel"), SummaryElement("button", "Delete"),
    ))

    private fun ev(kind: UiEventKind, source: UiSource = UiSource.WINDOW, window: WindowRef? = dialog, id: String = "e:1", appearedId: String? = null) =
        UiEvent(id, 1, 1, 1_793_000_000_000, kind, source, window = window, appearedId = appearedId)

    @Test
    fun lines() {
        assertEquals(
            "dialog appeared  \"Delete item?\"  · This cannot be undone. · Cancel · Delete  ⟂ network n7 in flight",
            UiEventText.line(ev(UiEventKind.WINDOW_APPEARED).copy(content = content, links = listOf(EventLink("network", "n7", LinkRelation.IN_FLIGHT)))),
        )
        assertEquals("dialog closed  \"Delete item?\"  after 1.21 s", UiEventText.line(ev(UiEventKind.WINDOW_DISAPPEARED).copy(durationMs = 1_210)))
        assertEquals("toast  \"Saved\" (short)", UiEventText.line(ev(UiEventKind.TOAST, UiSource.TOAST, null).copy(text = "Saved", durationMs = 2_000)))
        assertEquals(
            "not the app  permission controller: GrantPermissionsActivity appeared",
            UiEventText.line(ev(UiEventKind.WINDOW_APPEARED, UiSource.SCREEN_WINDOW, WindowRef("GrantPermissionsActivity", WindowKind.OTHER_APP, owner = "permission controller", app = false))),
        )
        assertEquals("process ended", UiEventText.line(ev(UiEventKind.PROCESS_ENDED, UiSource.JOURNAL, null)))
        assertTrue(
            UiEventText.line(ev(UiEventKind.GAP, UiSource.JOURNAL, null).copy(gapCount = 4, completeness = listOf(DegradationMarker("ui", "ui-events-dropped: 4 events lost"))))
                .startsWith("gap  4 events dropped"),
        )
    }

    @Test
    fun `the summary folds a window's life into one item`() {
        val events = listOf(
            ev(UiEventKind.WINDOW_APPEARED, id = "e:1"),
            ev(UiEventKind.WINDOW_SETTLED, id = "e:2", appearedId = "e:1"),
            ev(UiEventKind.WINDOW_DISAPPEARED, id = "e:3", appearedId = "e:1").copy(durationMs = 1_210),
            ev(UiEventKind.TOAST, UiSource.TOAST, null, id = "e:4").copy(text = "Saved"),
        )
        assertEquals(
            "Since your last call: 2 UI events — dialog \"Delete item?\" (closed after 1.2 s), toast \"Saved\".",
            UiEventText.summary(events),
        )
    }

    @Test
    fun `the summary is bounded and names the rest by count`() {
        val toasts = (1..8).map { ev(UiEventKind.TOAST, UiSource.TOAST, null, id = "e:$it").copy(text = "Toast number $it with a fairly long text") }
        val line = UiEventText.summary(toasts)!!
        assertTrue(line.length <= 200, line)
        assertTrue(line.startsWith("Since your last call: 8 UI events — "), line)
        assertTrue(line.endsWith("more."), line)
    }

    @Test
    fun `nothing to say says nothing`() {
        assertNull(UiEventText.summary(emptyList()))
    }
}
