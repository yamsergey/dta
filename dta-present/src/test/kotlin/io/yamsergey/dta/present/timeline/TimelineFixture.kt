package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.AppPayload
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NavPayload
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.ElementRef
import io.yamsergey.dta.protocol.ui.SummaryElement
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef

/** The 016 delete flow, with wall times from 1 000 ms (feature 017 tests). */
object TimelineFixture {
    fun id(seq: Long) = "7:$seq"

    private fun exact(from: Long, rule: String) = CauseLink(id(from), Basis.EXACT, rule)

    val click = TimelineEvent(
        id(1), 1, 100, 1_100, Sources.INPUT, Kinds.CLICK, "click delete_button",
        input = InputPayload(InputAction.CLICK, ElementRef("delete_button|Button"), "Delete", "w1", "view"),
    )
    val request = TimelineEvent(
        id(2), 2, 112, 1_112, Sources.NETWORK, Kinds.NETWORK_REQUEST, "DELETE /items/42",
        network = NetworkPayload("n7", NetworkPhase.REQUEST, "DELETE", "api.example.com", "/items/42"),
        causes = listOf(exact(1, CauseRules.ISSUED_DURING_CLICK)),
    )
    private val dialogRef = WindowRef("w2", WindowKind.DIALOG, title = "Deleting…")
    val dialog = TimelineEvent(
        id(3), 3, 115, 1_115, Sources.UI, Kinds.ui("window-appeared"),
        ui = UiEvent(
            id(3), 3, 115, 1_115, UiEventKind.WINDOW_APPEARED, UiSource.WINDOW, window = dialogRef,
            content = ContentSummary(listOf(SummaryElement("heading", text = "Deleting…"), SummaryElement("button", text = "Cancel"))),
        ),
        causes = listOf(exact(1, CauseRules.ISSUED_DURING_CLICK)),
    )
    val response = TimelineEvent(
        id(4), 4, 520, 1_520, Sources.NETWORK, Kinds.NETWORK_RESPONSE,
        network = NetworkPayload("n7", NetworkPhase.RESPONSE, "DELETE", "api.example.com", "/items/42", status = 200, durationMs = 408),
        causes = listOf(exact(2, CauseRules.SAME_EXCHANGE)),
    )
    val toast = TimelineEvent(
        id(5), 5, 554, 1_554, Sources.UI, Kinds.ui("toast"),
        ui = UiEvent(id(5), 5, 554, 1_554, UiEventKind.TOAST, UiSource.TOAST, text = "Deleted", durationMs = 2_000),
    )
    val removed = TimelineEvent(
        id(6), 6, 560, 1_560, Sources.APP, Kinds.app("cart.removed"),
        app = AppPayload("cart.removed", mapOf("id" to "42")),
        facets = listOf(FacetChange("app.cart", "empty", previous = "items")),
        causes = listOf(CauseLink(id(4), Basis.DECLARED, CauseRules.APP_DECLARED, declaredBy = Sources.APP)),
    )
    val navigate = TimelineEvent(
        id(7), 7, 600, 1_600, Sources.NAV, Kinds.NAV_NAVIGATE,
        nav = NavPayload("navigation", "navigate", "cart", mapOf("from" to "menu"), depth = 2),
        facets = listOf(FacetChange("nav.destination", "cart", previous = "catalog")),
    )
    val gap = TimelineEvent("host:9", 0, 0, 1_700, Sources.TIMELINE, Kinds.GAP, gapCount = 3)
    val ended = TimelineEvent("host:10", 0, 0, 1_800, Sources.TIMELINE, Kinds.PROCESS_ENDED)

    val flow = listOf(click, request, dialog, response, toast, removed, navigate)
}
