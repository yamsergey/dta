package io.yamsergey.dta.protocol.timeline

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.ui.ElementRef
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 016 (T002): the timeline envelope and the answers built on it. */
class TimelineModelTest {
    private val json = DtaJson

    private val click = TimelineEvent(
        id = "17:1", seq = 1, monoMs = 100, wallMs = 1_000_100,
        source = Sources.INPUT, kind = Kinds.CLICK, title = "click delete_button \"Delete\"",
        input = InputPayload(InputAction.CLICK, ElementRef(key = "delete_button|Button#0", role = "button"), "Delete", "w1", "view"),
    )
    private val request = TimelineEvent(
        id = "17:2", seq = 2, monoMs = 112, wallMs = 1_000_112,
        source = Sources.NETWORK, kind = Kinds.NETWORK_REQUEST, title = "DELETE /items/42",
        network = NetworkPayload("n7", NetworkPhase.REQUEST, "DELETE", "api.example.com", "/items/42"),
        causes = listOf(CauseLink("17:1", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)),
    )
    private val toast = TimelineEvent(
        id = "17:3", seq = 3, monoMs = 554, wallMs = 1_000_554, source = Sources.UI, kind = Kinds.ui("toast"), title = "toast \"Deleted\"",
        ui = UiEvent(id = "17:3", seq = 3, monoMs = 554, wallMs = 1_000_554, kind = UiEventKind.TOAST, source = UiSource.TOAST, text = "Deleted"),
    )
    private val app = TimelineEvent(
        id = "17:4", seq = 4, monoMs = 560, wallMs = 1_000_560, source = Sources.APP, kind = Kinds.app("cart.removed"),
        app = AppPayload("cart.removed", mapOf("id" to "42"), localId = 9),
        facets = listOf(FacetChange(Facets.app("cart"), "empty", previous = "items")),
        causes = listOf(CauseLink("local:3", Basis.DECLARED, CauseRules.APP_DECLARED, declaredBy = "app", fromUnknown = true)),
    )
    private val nav = TimelineEvent(
        id = "17:5", seq = 5, monoMs = 600, wallMs = 1_000_600, source = Sources.NAV, kind = Kinds.NAV_BACK,
        nav = NavPayload("navigation", "back", "catalog", mapOf("q" to "shoes"), depth = 1),
    )
    private val gap = TimelineEvent(
        id = "17:6", seq = 6, monoMs = 700, wallMs = 1_000_700, source = Sources.TIMELINE, kind = Kinds.GAP,
        gapCount = 4, gapFromMs = 650, gapToMs = 700, gapSource = Sources.APP,
    )

    @Test
    fun `every payload round-trips`() {
        for (e in listOf(click, request, toast, app, nav, gap)) {
            assertEquals(e, json.decodeFromString(TimelineEvent.serializer(), json.encodeToString(TimelineEvent.serializer(), e)))
        }
    }

    @Test
    fun `enums are kebab-case`() {
        val text = json.encodeToString(TimelineEvent.serializer(), request)
        assertTrue("\"basis\":\"exact\"" in text, text)
        assertTrue("\"phase\":\"request\"" in text, text)
        val chain = CauseChain(
            root = toast, direction = ChainDirection.CAUSES, nodes = listOf(toast), links = emptyList(),
            noKnownCause = listOf("17:3"), inferredCandidates = mapOf("17:3" to 1),
            cuts = listOf(Cut("17:3", CutReason.UNKNOWN_EVENT)),
        )
        val chainText = json.encodeToString(CauseChain.serializer(), chain)
        assertTrue("\"direction\":\"causes\"" in chainText && "\"reason\":\"unknown-event\"" in chainText, chainText)
        assertEquals(chain, json.decodeFromString(CauseChain.serializer(), chainText))
    }

    @Test
    fun `defaults keep the wire small`() {
        val text = json.encodeToString(TimelineEvent.serializer(), click)
        assertTrue("causes" !in text && "facets" !in text && "gapCount" !in text, text)
    }

    @Test
    fun `answers round-trip`() {
        val state = CurrentState(
            facets = listOf(FacetValue(Facets.app("cart"), "empty", Sources.APP, 560, 1_000_560, "17:4", Stale(900, "process-ended"))),
            sources = listOf(SourceStatus(Sources.NAV, SourceState.OFF, "not used by the app")),
            disagreements = listOf(Disagreement(listOf(Facets.NAV_DESTINATION, Facets.UI_WINDOWS), "navigation says cart, another activity is on top")),
        )
        assertEquals(state, json.decodeFromString(CurrentState.serializer(), json.encodeToString(CurrentState.serializer(), state)))
        val a = StateRef("s1", mapOf(Facets.app("cart") to "items"), "items")
        val graph = StateGraph(
            facets = listOf(Facets.app("cart")), unavailable = listOf(Facets.NAV_KEY),
            nodes = listOf(StateNode(a, 2, 3_000, current = true)),
            transitions = listOf(Transition("s1", "s1", 1, listOf("17:4"), noKnownCause = true, selfLoop = true)),
            current = "s1", window = GraphWindow(0, 900, partial = true),
        )
        assertEquals(graph, json.decodeFromString(StateGraph.serializer(), json.encodeToString(StateGraph.serializer(), graph)))
        val path = StatePath("s0", "s1", graph.transitions, found = true)
        assertEquals(path, json.decodeFromString(StatePath.serializer(), json.encodeToString(StatePath.serializer(), path)))
    }

    @Test
    fun `facet names know their source`() {
        assertEquals(Sources.APP, Facets.sourceOf(Facets.app("cart")))
        assertEquals(Sources.SCREEN, Facets.sourceOf(Facets.SCREEN_FINGERPRINT))
        assertEquals("issued-during:click", CauseRules.issuedDuring(Kinds.CLICK))
    }

    @Test
    fun `a compose click carries its chain and the read's main-thread time (064)`() {
        val chain = listOf(
            io.yamsergey.dta.protocol.layout.Frame("MarketsScreen", "BrokerNav.kt", 85, library = false, packageName = "com.example"),
            io.yamsergey.dta.protocol.layout.Frame("ListItem", "BrokerNav.kt", 103, library = false, packageName = "com.example"),
        )
        val tap = click.copy(input = click.input!!.copy(via = "compose", chain = chain, chainMainThreadMs = 6))
        assertEquals(tap, json.decodeFromString(TimelineEvent.serializer(), json.encodeToString(TimelineEvent.serializer(), tap)))
        val text = json.encodeToString(TimelineEvent.serializer(), click)
        assertTrue("chain" !in text && "chainMainThreadMs" !in text, text)
        val old = """{"action":"click","via":"compose"}"""
        assertEquals(InputPayload(InputAction.CLICK, via = "compose"), json.decodeFromString(InputPayload.serializer(), old))
        assertEquals("tap-chain-unavailable", io.yamsergey.dta.protocol.layout.DegradationCodes.TAP_CHAIN_UNAVAILABLE)
        assertEquals("tap-chain-partial", io.yamsergey.dta.protocol.layout.DegradationCodes.TAP_CHAIN_PARTIAL)
    }

    @Test
    fun `a scroll is one input event with where it went (064 C1)`() {
        val scroll = TimelineEvent(
            id = "17:9", seq = 9, monoMs = 1_000, wallMs = 2_000, source = Sources.INPUT, kind = Kinds.SCROLL, title = "scroll down markets",
            input = InputPayload(
                InputAction.SCROLL, ElementRef(key = "markets|LazyColumn"), via = "compose",
                scroll = ScrollInfo(endMonoMs = 1_650, direction = ScrollDirection.DOWN, from = ScrollPosition(0, 361), to = ScrollPosition(8, 393), fling = true, stoppedByTouch = false),
            ),
        )
        assertEquals("input/scroll", Kinds.SCROLL)
        val text = json.encodeToString(TimelineEvent.serializer(), scroll)
        assertTrue("\"action\":\"scroll\"" in text && "\"direction\":\"down\"" in text, text)
        assertEquals(scroll, json.decodeFromString(TimelineEvent.serializer(), text))
        assertEquals(ScrollPosition(null, 120), json.decodeFromString(ScrollPosition.serializer(), """{"offset":120}"""))
    }

    @Test
    fun `a scroll reads as its direction, positions and how it ended (064 C4)`() {
        val fling = ScrollInfo(1, ScrollDirection.DOWN, ScrollPosition(0, 361), ScrollPosition(8, 393), fling = true)
        assertEquals("scroll ↓ 0+361 → 8+393 fling", ScrollText.line(fling))
        val touched = ScrollInfo(1, ScrollDirection.DOWN, ScrollPosition(8, 393), ScrollPosition(9, 12), stoppedByTouch = true)
        assertEquals("scroll ↓ 8+393 → 9+12 · stopped by a touch", ScrollText.line(touched))
        assertEquals("scroll ← 300 → 100", ScrollText.line(ScrollInfo(1, ScrollDirection.LEFT, ScrollPosition(null, 300), ScrollPosition(null, 100))))
        assertEquals("item 8, 393 px into it", ScrollText.position(ScrollPosition(8, 393)))
        assertEquals("393 px", ScrollText.position(ScrollPosition(null, 393)))
    }
}
