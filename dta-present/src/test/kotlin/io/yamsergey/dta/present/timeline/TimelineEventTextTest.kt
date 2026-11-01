package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.AppPayload
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.ElementRef
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 016: how the timeline reads in the CLI and in the agent's summary line. */
class TimelineEventTextTest {
    private val click = TimelineEvent("7:1", 1, 100, 1, Sources.INPUT, Kinds.CLICK, input = InputPayload(InputAction.CLICK, ElementRef("content/delete_button|Button#0"), "Delete", via = "view"))
    private val request = TimelineEvent(
        "7:2", 2, 112, 2, Sources.NETWORK, Kinds.NETWORK_REQUEST,
        network = NetworkPayload("n7", NetworkPhase.REQUEST, "DELETE", "api.example.com", "/items/42"),
        causes = listOf(CauseLink("7:1", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)),
    )
    private val response = request.copy(id = "7:3", seq = 3, kind = Kinds.NETWORK_RESPONSE, network = request.network!!.copy(phase = NetworkPhase.RESPONSE, status = 200, durationMs = 408), causes = listOf(CauseLink("7:2", Basis.EXACT, CauseRules.SAME_EXCHANGE)))
    private val toast = TimelineEvent("7:4", 4, 554, 4, Sources.UI, Kinds.ui("toast"), ui = UiEvent("7:4", 4, 554, 4, UiEventKind.TOAST, UiSource.TOAST, text = "Deleted"))
    private val removed = TimelineEvent(
        "7:5", 5, 560, 5, Sources.APP, Kinds.app("cart.removed"), app = AppPayload("cart.removed", mapOf("id" to "42")),
        facets = listOf(FacetChange("app.cart", "empty", "items")),
    )

    @Test
    fun `lines name the source, what happened and the known cause`() {
        val byId = listOf(click, request).associateBy { it.id }
        assertEquals("input    click delete_button \"Delete\"", TimelineEventText.line(click))
        assertEquals("network  DELETE /items/42  ← click delete_button \"Delete\" (exact)", TimelineEventText.line(request, byId::get))
        assertEquals("ui       toast  \"Deleted\"  · no known cause", TimelineEventText.line(toast))
        assertEquals("app      cart.removed id=42  · app.cart items → empty  · no known cause", TimelineEventText.line(removed))
    }

    @Test
    fun `the summary folds a request into its response and names facet changes`() {
        assertEquals(
            "Since your last call: 4 events — click delete_button \"Delete\", DELETE /items/42 → 200 in 408 ms, toast \"Deleted\", app: cart.removed, app.cart items → empty.",
            TimelineEventText.summary(listOf(click, request, response, toast, removed)),
        )
        assertNull(TimelineEventText.summary(emptyList()))
    }

    @Test
    fun `a chain reads root first with the basis of each link and what is unknown`() {
        val chain = CauseChain(
            root = response, direction = ChainDirection.CAUSES, nodes = listOf(response, request, click),
            links = listOf(ChainLink("7:2", "7:3", response.causes.single()), ChainLink("7:1", "7:2", request.causes.single())),
        )
        val text = TimelineEventText.chain(chain)
        val lines = text.lines()
        assertTrue(lines[0].startsWith("7:3  network  DELETE /items/42 → 200"), text)
        assertTrue(lines[1].startsWith("  ← exact same-exchange  7:2"), text)
        assertTrue(lines[2].startsWith("    ← exact issued-during:click  7:1  input"), text)
        val unknown = CauseChain(toast, ChainDirection.CAUSES, listOf(toast), emptyList(), noKnownCause = listOf("7:4"), inferredCandidates = mapOf("7:4" to 1))
        assertTrue("no known cause (1 inferred, ask with --inferred)" in TimelineEventText.chain(unknown))
    }
}
