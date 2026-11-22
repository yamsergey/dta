package io.yamsergey.dta.protocol.timeline

import io.yamsergey.dta.protocol.network.Redactor
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.SummaryElement
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Decision 2026-11-22 (3): the timeline, the tap chain and the app's state are redacted as network outputs are. */
class TimelineRedactionTest {
    private val secrets = listOf("SEED-T1", "SEED-T2", "SEED-T3", "SEED-T4", "SEED-T5", "SEED-T6", "SEED-T7", "SEED-T8", "SEED-T9")

    private fun leaks(text: String) = secrets.filter { it in text }

    private val event = TimelineEvent(
        id = "7:1", seq = 1, monoMs = 1, wallMs = 1, source = Sources.NETWORK, kind = Kinds.NETWORK_FAILED,
        title = "POST /login failed: token=SEED-T1",
        network = NetworkPayload("r1:1", NetworkPhase.FAILED, method = "POST", host = "api.example.test", path = "/login", error = "java.io.IOException: https://api.example.test/login?access_token=SEED-T2"),
        nav = NavPayload("navigation", "navigate", destination = "reset?session=SEED-T3&step=2", args = mapOf("password" to "SEED-T4", "step" to "2")),
        app = AppPayload("checkout", attrs = mapOf("card" to "SEED-T5", "note" to "Bearer SEED-T6.abc", "items" to "3")),
        facets = listOf(FacetChange("nav.args", "step=2, token=SEED-T7", previous = "step=1"), FacetChange("app.apiKey", "SEED-T8")),
        causes = listOf(CauseLink("7:0", Basis.INFERRED, CauseRules.TEXT_MATCH, detail = "the response holds \"secret\":\"SEED-T9\"")),
        ui = UiEvent("7:1", 1, 1, 1, UiEventKind.TOAST, UiSource.TOAST, text = "Saved, email: ana@example.test",
            content = ContentSummary(listOf(SummaryElement("text", text = "password=hunter22 here", description = "step 2")))),
    )

    @Test
    fun `an event is masked in every free text and named value, the rest kept`() {
        val (e, info) = TimelineRedaction.event(event)
        val text = DtaJson.encodeToString(TimelineEvent.serializer(), e)
        assertEquals(emptyList(), leaks(text), text)
        assertFalse("ana@example.test" in text || "hunter22" in text, text)
        assertEquals("7:1", e.id, "ids are kept")
        assertEquals("2", e.nav!!.args["step"])
        assertEquals("3", e.app!!.attrs["items"])
        assertEquals("step=1", e.facets.first().previous)
        assertEquals("/login", e.network!!.path)
        assertTrue("step=2" in e.nav!!.destination, e.nav!!.destination)
        for (place in listOf("title", "network error", "nav destination", "nav arg password", "app attr card", "app attr note", "facet nav.args", "facet app.apiKey", "cause detail", "ui text", "ui content[0] text")) {
            assertTrue(info.masked.any { "event 7:1 $place" in it }, "masked names $place: ${info.masked}")
        }
    }

    @Test
    fun `pages, chains, state and paths are masked and say so`() {
        val page = TimelineRedaction.page(TimelinePage(listOf(event), next = "s:1"))
        assertEquals(emptyList(), leaks(DtaJson.encodeToString(TimelinePage.serializer(), page)))
        assertTrue(page.redaction!!.masked.isNotEmpty())
        val chain = TimelineRedaction.chain(CauseChain(event, ChainDirection.CAUSES, listOf(event), listOf(ChainLink("7:0", "7:1", event.causes.single()))))
        assertEquals(emptyList(), leaks(DtaJson.encodeToString(CauseChain.serializer(), chain)))
        val current = TimelineRedaction.current(CurrentState(listOf(FacetValue("nav.args", "token=SEED-T7", "nav", 1, 1), FacetValue("app.secret", "SEED-T8", "app", 1, 1), FacetValue("nav.destination", "home", "nav", 1, 1)), emptyList()))
        assertEquals(emptyList(), leaks(DtaJson.encodeToString(CurrentState.serializer(), current)))
        assertEquals("home", current.facets.last().value)
        val ref = StateRef("s1", linkedMapOf("nav.destination" to "home", "app.token" to "SEED-T1"), label = "home · SEED-T1")
        val graph = TimelineRedaction.graph(StateGraph(listOf("nav.destination", "app.token"), nodes = listOf(StateNode(ref, 1, 1)), transitions = listOf(Transition("s0", "s1", 1, listOf("7:1"), causes = event.causes)), window = GraphWindow(0, 1)))
        val g = DtaJson.encodeToString(StateGraph.serializer(), graph)
        assertEquals(emptyList(), leaks(g), g)
        assertEquals("home · ${Redactor.MASK}", graph.nodes.single().state.label)
        assertEquals("s1", graph.nodes.single().state.id, "state ids are kept, so a path asked redacted names the same states")
        val path = TimelineRedaction.path(StatePath("s0", "s1", graph.transitions.map { it.copy(causes = event.causes) }, found = true))
        assertEquals(emptyList(), leaks(DtaJson.encodeToString(StatePath.serializer(), path)))
    }

    @Test
    fun `a sequence is built from masked events and socket entries`() {
        val socket = io.yamsergey.dta.protocol.network.WsConnectionEntry("r1:s", "r1", 1, "okhttp3-ws", url = "wss://h/live?token=SEED-T2", state = io.yamsergey.dta.protocol.network.WsState.OPEN)
        val (events, sockets, info) = TimelineRedaction.sequenceInput(listOf(event), listOf(socket))
        assertEquals(emptyList(), leaks(events.toString() + sockets.toString()))
        assertTrue(info.masked.any { "url.query token" in it }, info.masked.toString())
    }
}
