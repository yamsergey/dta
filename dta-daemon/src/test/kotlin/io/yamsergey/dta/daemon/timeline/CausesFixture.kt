package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.AppPayload
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef

/** The delete flow of the design (feature 016): tap → request → dialog → response → dialog closed → toast → cart.removed. */
object CausesFixture {
    const val E = 7L

    fun id(seq: Long) = "$E:$seq"

    private fun exact(from: Long, rule: String) = CauseLink(id(from), Basis.EXACT, rule)

    val click = TimelineEvent(id(1), 1, 100, 1_100, Sources.INPUT, Kinds.CLICK, "click delete_button", input = InputPayload(InputAction.CLICK, text = "Delete", via = "view"))
    val request = TimelineEvent(
        id(2), 2, 112, 1_112, Sources.NETWORK, Kinds.NETWORK_REQUEST, "DELETE /items/42",
        network = NetworkPayload("n7", NetworkPhase.REQUEST, "DELETE", "api.example.com", "/items/42"),
        causes = listOf(exact(1, CauseRules.ISSUED_DURING_CLICK)),
    )
    private val dialogRef = WindowRef("w2", WindowKind.DIALOG, title = "Deleting…")
    val dialog = TimelineEvent(
        id(3), 3, 115, 1_115, Sources.UI, Kinds.ui("window-appeared"), "dialog \"Deleting…\"",
        ui = UiEvent(id(3), 3, 115, 1_115, UiEventKind.WINDOW_APPEARED, UiSource.WINDOW, window = dialogRef),
        causes = listOf(exact(1, CauseRules.ISSUED_DURING_CLICK)),
    )
    val response = TimelineEvent(
        id(4), 4, 520, 1_520, Sources.NETWORK, Kinds.NETWORK_RESPONSE, "DELETE /items/42 → 200",
        network = NetworkPayload("n7", NetworkPhase.RESPONSE, "DELETE", "api.example.com", "/items/42", status = 200, durationMs = 408),
        causes = listOf(exact(2, CauseRules.SAME_EXCHANGE)),
    )
    val dialogClosed = TimelineEvent(
        id(5), 5, 530, 1_530, Sources.UI, Kinds.ui("window-disappeared"), "dialog closed",
        ui = UiEvent(id(5), 5, 530, 1_530, UiEventKind.WINDOW_DISAPPEARED, UiSource.WINDOW, window = dialogRef, appearedId = id(3)),
        causes = listOf(exact(3, CauseRules.SAME_WINDOW)),
    )
    val toast = TimelineEvent(
        id(6), 6, 554, 1_554, Sources.UI, Kinds.ui("toast"), "toast \"Deleted\"",
        ui = UiEvent(id(6), 6, 554, 1_554, UiEventKind.TOAST, UiSource.TOAST, text = "Deleted"),
    )
    val removed = TimelineEvent(
        id(7), 7, 560, 1_560, Sources.APP, Kinds.app("cart.removed"), "cart.removed",
        app = AppPayload("cart.removed", mapOf("id" to "42")),
        facets = listOf(FacetChange("app.cart", "empty", previous = "items")),
        causes = listOf(CauseLink(id(4), Basis.DECLARED, CauseRules.APP_DECLARED, declaredBy = Sources.APP)),
    )

    val flow = listOf(click, request, dialog, response, dialogClosed, toast, removed)
}
