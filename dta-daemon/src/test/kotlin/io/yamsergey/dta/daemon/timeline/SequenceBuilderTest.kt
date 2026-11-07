package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NavPayload
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.SequenceLanes
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.ElementRef
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequenceBuilderTest {
    private var seq = 100L
    private fun ev(ms: Long, source: String, kind: String, epoch: Long = 7, s: Long = seq++, build: TimelineEvent.() -> TimelineEvent = { this }) =
        TimelineEvent("$epoch:$s", s, ms, ms, source, kind).build()

    private fun tap(ms: Long, key: String, text: String? = null) =
        ev(ms, Sources.INPUT, Kinds.CLICK) { copy(input = InputPayload(InputAction.CLICK, ElementRef(key), text, via = "compose")) }
    private fun nav(ms: Long, to: String, from: TimelineEvent? = null, previous: String? = null) = ev(ms, Sources.NAV, Kinds.NAV_NAVIGATE) {
        copy(
            nav = NavPayload("navigation", "navigate", to),
            facets = listOf(FacetChange(Facets.NAV_DESTINATION, to, previous)),
            causes = listOfNotNull(from?.let { CauseLink(it.id, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK) }),
        )
    }
    private fun req(ms: Long, path: String, host: String = "h:1", id: String = "e$ms", method: String = "GET", causes: List<CauseLink> = emptyList()) = ev(ms, Sources.NETWORK, Kinds.NETWORK_REQUEST) {
        copy(network = NetworkPayload(id, NetworkPhase.REQUEST, method, host, path), causes = causes)
    }
    private fun res(ms: Long, status: Int, took: Long, id: String, host: String = "h:1") = ev(ms, Sources.NETWORK, Kinds.NETWORK_RESPONSE) {
        copy(network = NetworkPayload(id, NetworkPhase.RESPONSE, "GET", host, "/", status = status, durationMs = took))
    }
    private fun screen(ms: Long, activity: String? = "MainActivity") = ev(ms, Sources.SCREEN, SequenceBuilder.SCREEN_CHANGED) {
        copy(facets = listOf(FacetChange(Facets.SCREEN_FINGERPRINT, "${activity ?: "screen"}·0000$ms")))
    }
    private fun body(text: String) = BodyInfo(BodyPart.MESSAGE, text.length.toLong(), text.length.toLong(), completeness = Completeness.COMPLETE, preview = text)
    private fun ws(ms: Long, dir: Direction, text: String) = WsMessageEntry("r:$ms", "r", ms, "okhttp3-ws", null, "c", dir, MessageOpcode.TEXT, body(text))
    private fun labels(events: List<TimelineEvent>, q: String? = null) = SequenceBuilder().build(events, emptyList(), q = q).steps.map { it.label }

    @Test
    fun `rows stay in time order with nothing between them, however long the pause`() {
        val t = tap(1_000, "row:ACME")
        val page = SequenceBuilder().build(listOf(req(9_000, "/later"), t, req(6_000, "/a")), emptyList())
        assertEquals(listOf("tap row:ACME", "GET /a", "GET /later"), page.steps.map { it.label })
        assertEquals(3, page.total)
        assertEquals(1_000, page.startWallMs)
        assertFalse(page.steps.any { it.boundary })
    }

    @Test
    fun `only a kept exact or declared cause links a row, the hover says which, timing never does`() {
        val t = tap(1_000, "go", "Go")
        val gone = CauseLink("7:1", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)
        val inferred = CauseLink(t.id, Basis.INFERRED, CauseRules.AFTER_RESPONSE)
        val steps = SequenceBuilder().build(
            listOf(t, nav(1_001, "Home", t), req(1_002, "/a", causes = listOf(inferred)), req(1_003, "/b", causes = listOf(gone)), req(30_000, "/c", causes = listOf(CauseLink(t.id, Basis.DECLARED, CauseRules.APP_SCOPE)))),
            emptyList(),
        ).steps
        assertEquals(listOf(false, true, false, false, true), steps.map { it.linked })
        assertTrue("tap \"Go\"" in steps[1].detail!!, steps[1].detail)
    }

    @Test
    fun `received socket messages join until another row comes between them`() {
        val page = SequenceBuilder().build(
            listOf(tap(1_000, "a"), tap(5_000, "b")),
            listOf(ws(2_000, Direction.IN, """{"type":"tick"}"""), ws(2_500, Direction.IN, """{"type":"tick"}"""), ws(3_000, Direction.IN, """{"type":"quote"}"""), ws(6_000, Direction.IN, """{"type":"tick"}""")),
        )
        assertEquals(listOf("tap a", "tick ×2, quote ×1", "tap b", "tick ×1"), page.steps.map { it.label })
        assertEquals(4, page.socketMessages)
        assertEquals(SequenceLanes.SOCKET, page.steps[1].from)
        assertTrue(page.steps[1].back)
        assertEquals("r:2000", page.steps[1].entryId)
    }

    @Test
    fun `what comes right back after a sent message stays its own row`() {
        val page = SequenceBuilder().build(
            listOf(tap(1_000, "a")),
            listOf(ws(1_100, Direction.OUT, """{"type":"subscribe"}"""), ws(1_200, Direction.IN, """{"type":"subscribed"}"""), ws(1_300, Direction.IN, """{"type":"tick"}""")),
        )
        assertEquals(listOf("tap a", """{"type":"subscribe"}""", """{"type":"subscribed"}""", "tick ×1"), page.steps.map { it.label })
    }

    @Test
    fun `socket open and close are rows, a failure is marked`() {
        val open = WsConnectionEntry("c", "r", 1_000, "okhttp3-ws", null, "ws://h:1/stream", WsState.CLOSED, closedAtMs = 2_000, closeCode = 1000)
        val page = SequenceBuilder().build(listOf(tap(500, "a")), listOf(open))
        assertEquals(listOf("tap a", "open /stream", "closed 1000"), page.steps.map { it.label })
    }

    @Test
    fun `a process start, a process end and a gap are boundaries`() {
        val first = ev(1_000, Sources.NETWORK, Kinds.NETWORK_REQUEST, s = 1) { copy(network = NetworkPayload("a", NetworkPhase.REQUEST, "GET", "h:1", "/a")) }
        val ended = ev(2_000, Sources.TIMELINE, Kinds.PROCESS_ENDED)
        val gap = ev(1_500, Sources.TIMELINE, Kinds.GAP)
        val second = ev(3_000, Sources.NETWORK, Kinds.NETWORK_REQUEST, epoch = 8, s = 1) { copy(network = NetworkPayload("b", NetworkPhase.REQUEST, "GET", "h:1", "/b")) }
        val steps = SequenceBuilder().build(listOf(first, gap, ended, second), emptyList()).steps
        assertEquals(listOf("process started", "GET /a", "events may be missing", "process ended", "process started", "GET /b"), steps.map { it.label })
        assertEquals(listOf(true, false, true, true, true, false), steps.map { it.boundary })
    }

    @Test
    fun `a start evicted from what is kept is not called a start`() {
        assertEquals(listOf("GET /a"), labels(listOf(req(1_000, "/a"))))
    }

    @Test
    fun `the element journal's own back-off is not stated, elements are left out`() {
        val elementGap = ev(1_000, Sources.TIMELINE, Kinds.GAP) { copy(ui = UiEvent("g", 0, 0, 1_000, UiEventKind.ELEMENT_APPEARED, UiSource.ELEMENT)) }
        val element = ev(1_100, Sources.UI, Kinds.ui("element-appeared")) { copy(ui = UiEvent("h", 0, 0, 1_100, UiEventKind.ELEMENT_APPEARED, UiSource.ELEMENT)) }
        assertEquals(listOf("tap a"), labels(listOf(elementGap, element, tap(1_200, "a"))))
    }

    @Test
    fun `a screen names the destination and the Activity`() {
        val t = tap(1_000, "row")
        assertEquals(
            listOf("tap row", "→ InstrumentRoute/{symbol}", "screen InstrumentRoute/{symbol} · MainActivity"),
            labels(listOf(t, nav(1_000, "a.b.ui.InstrumentRoute/{symbol}"), screen(2_000))),
        )
    }

    @Test
    fun `the same screen again says the content changed`() {
        assertEquals(
            listOf("→ Home", "screen Home · MainActivity", "content changed · Home · MainActivity", "→ Cart", "screen Cart · MainActivity"),
            labels(listOf(nav(1_000, "a.Home"), screen(1_500), screen(9_000), nav(10_000, "a.Cart"), screen(11_000))),
        )
    }

    @Test
    fun `with no navigation the Activity alone says it, new only when it changed`() {
        assertEquals(
            listOf("screen MainActivity", "content changed · MainActivity", "screen OtherActivity"),
            labels(listOf(screen(1_000), screen(2_000), screen(3_000, "OtherActivity"))),
        )
    }

    @Test
    fun `knowing neither says plainly that the screen changed`() {
        assertEquals(listOf("screen changed"), labels(listOf(screen(1_000, null))))
    }

    @Test
    fun `a destination evicted from what is kept comes from the first navigation's previous value`() {
        assertEquals(
            listOf("screen Home · MainActivity", "→ Cart"),
            labels(listOf(screen(1_000), nav(2_000, "a.Cart", previous = "a.Home"))),
        )
    }

    @Test
    fun `a new process starts over what it knows of screens`() {
        val second = ev(5_000, Sources.SCREEN, SequenceBuilder.SCREEN_CHANGED, epoch = 8, s = 50) { copy(facets = listOf(FacetChange(Facets.SCREEN_FINGERPRINT, "MainActivity·1"))) }
        assertEquals(
            listOf("→ Home", "screen Home · MainActivity", "process started", "screen MainActivity"),
            labels(listOf(nav(1_000, "a.Home"), screen(2_000), second)),
        )
    }

    @Test
    fun `the hover gives the full destination and the Activity's class when a window named it`() {
        val window = ev(900, Sources.UI, Kinds.ui("window-appeared")) {
            copy(ui = UiEvent("w", 0, 0, 900, UiEventKind.WINDOW_APPEARED, UiSource.SCREEN_WINDOW, window = WindowRef("1", WindowKind.ACTIVITY, owner = "a.b.MainActivity")))
        }
        val step = SequenceBuilder().build(listOf(window, nav(1_000, "a.b.ui.Home"), screen(2_000)), emptyList()).steps.last()
        assertEquals("a.b.ui.Home · a.b.MainActivity", step.detail)
    }

    @Test
    fun `a filter keeps the exchange that matches, its request with its response, and drops the rest`() {
        val events = listOf(tap(1_000, "a"), req(1_100, "/ok", id = "1"), res(1_200, 200, 5, "1"), req(1_300, "/bad", id = "2"), res(1_400, 503, 5, "2"), screen(1_500))
        val page = SequenceBuilder().build(events, emptyList(), q = "status:5xx")
        assertEquals(listOf("GET /bad", "503 · 5 ms"), page.steps.map { it.label })
        assertEquals(6, page.total)
        assertEquals(2, page.matched)
    }

    @Test
    fun `method, host, url, kind and negation read the same as in Network`() {
        val events = listOf(tap(1_000, "a"), req(1_100, "/x", host = "api.example.test", id = "1", method = "POST"), req(1_200, "/y", host = "cdn.example.test:8080", id = "2"))
        assertEquals(listOf("POST /x"), labels(events, "POST"))
        assertEquals(listOf("GET /y"), labels(events, "host:cdn.example.test"))
        assertEquals(listOf("POST /x", "GET /y"), labels(events, "host:*.example.test"))
        assertEquals(listOf("GET /y"), labels(events, "url:/y"))
        assertEquals(listOf("POST /x", "GET /y"), labels(events, "http"))
        assertEquals(listOf("tap a", "GET /y"), labels(events, "-host:api.example.test"))
    }

    @Test
    fun `pending and failed read the exchange's outcome`() {
        val failed = ev(1_300, Sources.NETWORK, Kinds.NETWORK_FAILED) { copy(network = NetworkPayload("2", NetworkPhase.FAILED, "GET", "h:1", "/b", error = "timeout")) }
        val events = listOf(req(1_000, "/a", id = "1"), req(1_100, "/b", id = "2"), failed, req(1_400, "/c", id = "3"), res(1_500, 200, 1, "3"))
        assertEquals(listOf("GET /a"), labels(events, "status:pending"))
        assertEquals(listOf("GET /b", "failed: timeout"), labels(events, "status:failed"))
    }

    @Test
    fun `ws keeps socket rows and a line the sequence cannot answer is refused`() {
        val page = SequenceBuilder().build(listOf(tap(1_000, "a")), listOf(ws(1_100, Direction.IN, """{"type":"tick"}""")), q = "ws")
        assertEquals(listOf("tick ×1"), page.steps.map { it.label })
        for (q in listOf("body:x", "run:current", "has:intervention", "nonsense:x", "status:9")) {
            assertFailsWith<io.yamsergey.dta.protocol.rpc.InvalidParamsException>(q) { SequenceBuilder.parse(q) }
        }
    }

    @Test
    fun `search words mark rows and remove none, in the label or the address`() {
        val events = listOf(tap(1_000, "row:ACME"), req(1_100, "/quote/acme", id = "1"), req(1_200, "/other", host = "acme.example.test", id = "2"), req(1_300, "/none", id = "3"))
        val page = SequenceBuilder().build(events, emptyList(), q = "ACME")
        assertEquals(listOf(true, true, true, false), page.steps.map { it.hit })
        assertEquals(3, page.hits)
        assertEquals(4, page.matched)
        assertEquals(0, SequenceBuilder().build(events, emptyList(), q = "ACME nothing").hits)
    }

    @Test
    fun `the start moment leaves out earlier rows and says how many, times still count from the first`() {
        val page = SequenceBuilder().build(listOf(tap(1_000, "a"), tap(2_000, "b"), tap(3_000, "c")), emptyList(), sinceMs = 2_000)
        assertEquals(listOf("tap b", "tap c"), page.steps.map { it.label })
        assertEquals(2, page.total)
        assertEquals(1, page.beforeStart)
        assertEquals(1_000, page.startWallMs)
    }

    @Test
    fun `the newest come within the limit`() {
        val page = SequenceBuilder().build(listOf(tap(1_000, "a"), tap(2_000, "b"), tap(3_000, "c")), emptyList(), limit = 2)
        assertEquals(listOf("tap b", "tap c"), page.steps.map { it.label })
        assertEquals(3, page.total)
    }

    @Test
    fun `the busiest hosts have a lane, the rest share one, no socket means no socket lane`() {
        val events = listOf(req(1, "/", host = "a", id = "1"), req(2, "/", host = "a", id = "2"), req(3, "/", host = "b", id = "3"), req(4, "/", host = "b", id = "4"), req(5, "/", host = "c", id = "5"))
        val page = SequenceBuilder().build(events, emptyList())
        assertEquals(listOf("You", "App", "a", "b", "other hosts"), page.lanes.map { it.title })
        assertEquals(SequenceLanes.OTHER, page.steps.last().to)
    }
}
