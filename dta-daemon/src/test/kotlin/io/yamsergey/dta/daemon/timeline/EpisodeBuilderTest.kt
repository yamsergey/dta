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
import io.yamsergey.dta.protocol.timeline.EpisodeLanes
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NavPayload
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.SocketCount
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.ElementRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeBuilderTest {
    private var seq = 100L
    private fun ev(ms: Long, source: String, kind: String, epoch: Long = 7, s: Long = seq++, build: TimelineEvent.() -> TimelineEvent = { this }) =
        TimelineEvent("$epoch:$s", s, ms, ms, source, kind).build()

    private fun tap(ms: Long, key: String, text: String? = null) =
        ev(ms, Sources.INPUT, Kinds.CLICK) { copy(input = InputPayload(InputAction.CLICK, ElementRef(key), text, via = "compose")) }
    private fun back(ms: Long) = ev(ms, Sources.INPUT, Kinds.BACK) { copy(input = InputPayload(InputAction.BACK, via = "dispatcher")) }
    private fun nav(ms: Long, to: String, from: TimelineEvent? = null) = ev(ms, Sources.NAV, Kinds.NAV_NAVIGATE) {
        copy(nav = NavPayload("navigation", "navigate", to), causes = listOfNotNull(from?.let { CauseLink(it.id, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK) }))
    }
    private fun req(ms: Long, path: String, host: String = "h:1", id: String = "e$ms", causes: List<CauseLink> = emptyList()) = ev(ms, Sources.NETWORK, Kinds.NETWORK_REQUEST) {
        copy(network = NetworkPayload(id, NetworkPhase.REQUEST, "GET", host, path), causes = causes)
    }
    private fun res(ms: Long, status: Int, took: Long, id: String, host: String = "h:1") = ev(ms, Sources.NETWORK, Kinds.NETWORK_RESPONSE) {
        copy(network = NetworkPayload(id, NetworkPhase.RESPONSE, "GET", host, "/", status = status, durationMs = took), causes = listOf(CauseLink("7:1", Basis.EXACT, CauseRules.SAME_EXCHANGE)))
    }
    private fun screen(ms: Long) = ev(ms, Sources.SCREEN, EpisodeBuilder.SCREEN_CHANGED)
    private fun body(text: String) = BodyInfo(BodyPart.MESSAGE, text.length.toLong(), text.length.toLong(), completeness = Completeness.COMPLETE, preview = text)
    private fun ws(ms: Long, dir: Direction, text: String) = WsMessageEntry("r:$ms", "r", ms, "okhttp3-ws", null, "c", dir, MessageOpcode.TEXT, body(text))

    @Test
    fun `a tap starts an episode and takes in what follows, its title says where it went`() {
        val t = tap(1_000, "row:ACME", "ACME · Acme Corp NYSE · Industrials 20.73")
        val page = EpisodeBuilder().build(
            listOf(t, nav(1_000, "a.b.ui.InstrumentRoute/{symbol}", t), req(1_040, "/quote/ACME"), res(1_050, 200, 11, "e1040"), screen(2_000)),
            emptyList(),
        )
        val e = page.episodes.single()
        assertEquals("Tap row:ACME → Instrument", e.title)
        assertEquals(1, e.requests)
        assertTrue(e.screenChanged)
        assertEquals(listOf("tap row:ACME", "→ InstrumentRoute/{symbol}", "GET /quote/ACME", "200 · 11 ms", "screen changed"), e.steps.map { it.label })
    }

    @Test
    fun `only recorded causes inside the episode link a step, timing never does`() {
        val t = tap(1_000, "go", "Go")
        val outside = CauseLink("7:1", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)
        val inferred = CauseLink(t.id, Basis.INFERRED, CauseRules.AFTER_RESPONSE)
        val steps = EpisodeBuilder().build(
            listOf(t, nav(1_001, "Home", t), req(1_002, "/a", causes = listOf(inferred)), req(1_003, "/b", causes = listOf(outside)), req(1_004, "/c")),
            emptyList(),
        ).episodes.single().steps
        assertEquals(listOf(false, true, false, false, false), steps.map { it.linked })
    }

    @Test
    fun `a request issued in the tap's handler is linked, its response is a return and is not`() {
        val t = tap(1_000, "go", "Go")
        val steps = EpisodeBuilder().build(
            listOf(t, req(1_001, "/a", causes = listOf(CauseLink(t.id, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK))), res(1_002, 500, 1, "e1001")),
            emptyList(),
        ).episodes.single().steps
        assertTrue(steps[1].linked)
        assertEquals(EpisodeLanes.host("h:1"), steps[1].to)
        assertEquals("e1001", steps[1].entryId)
        assertTrue(steps[2].back && steps[2].failed && !steps[2].linked)
    }

    @Test
    fun `a pause starts an episode, a second navigation too, and socket messages never do`() {
        val page = EpisodeBuilder().build(
            listOf(req(1_000, "/a"), req(2_999, "/b"), req(5_000, "/c"), nav(5_100, "A"), nav(5_200, "B")),
            listOf(ws(3_500, Direction.IN, "{\"type\":\"tick\"}"), ws(4_500, Direction.IN, "{\"type\":\"tick\"}")),
        )
        assertEquals(listOf("GET /a", "→ A", "→ B"), page.episodes.map { it.title })
        assertEquals(listOf(SocketCount("tick", 2)), page.episodes[0].socketCounts)
        assertEquals(2, page.socketMessages)
    }

    @Test
    fun `back and a new process each start one, and only a kept process start is called app start`() {
        val page = EpisodeBuilder().build(
            listOf(
                ev(1, Sources.NAV, Kinds.NAV_NAVIGATE, epoch = 1, s = 3) { copy(nav = NavPayload("navigation", "navigate", "a.MarketsRoute")) },
                back(100),
                ev(200, Sources.SCREEN, EpisodeBuilder.SCREEN_CHANGED, epoch = 2, s = 900),
            ),
            emptyList(),
        )
        assertEquals(listOf("App start → Markets", "Back", "Screen changed"), page.episodes.map { it.title })
    }

    @Test
    fun `what was sent is drawn with what came right back, the rest is counted by name`() {
        val page = EpisodeBuilder().build(
            listOf(tap(1_000, "star", "☆")),
            listOf(
                WsConnectionEntry("c", "r", 900, "okhttp3-ws", url = "ws://h:1/stream", state = WsState.OPEN),
                ws(1_001, Direction.OUT, "{\"subscribe\":[\"G\"]}"),
                ws(1_002, Direction.IN, "{\"type\":\"subscribed\"}"),
                ws(1_500, Direction.IN, "{\"type\":\"tick\"}"),
                ws(1_600, Direction.IN, "plain"),
            ),
        )
        val e = page.episodes.single()
        assertEquals(1, e.socketSent)
        assertTrue(e.socketOpened)
        assertEquals(listOf("open /stream", "tap \"☆\"", "{\"subscribe\":[\"G\"]}", "{\"type\":\"subscribed\"}"), e.steps.map { it.label })
        assertEquals(listOf(SocketCount("tick", 1), SocketCount("message", 1)), e.socketCounts)
        assertEquals(EpisodeLanes.SOCKET, page.lanes.last().id)
    }

    @Test
    fun `the busiest hosts have a lane, the rest share one, and no socket means no socket lane`() {
        val page = EpisodeBuilder().build(
            listOf(req(1, "/", "a"), req(2, "/", "a"), req(3, "/", "b"), req(4, "/", "b"), req(5, "/", "c")),
            emptyList(),
        )
        assertEquals(listOf("You", "App", "a", "b", "other hosts"), page.lanes.map { it.title })
        assertEquals(EpisodeLanes.OTHER, page.episodes.single().steps.last().to)
    }

    @Test
    fun `gaps are stated on the episode, not drawn, window settle and elements are left out`() {
        val page = EpisodeBuilder().build(
            listOf(req(1, "/"), ev(2, Sources.TIMELINE, Kinds.GAP), ev(3, Sources.UI, "ui/element-appeared")),
            emptyList(),
        )
        assertEquals(1, page.episodes.single().gaps)
        assertEquals(1, page.episodes.single().steps.size)
    }

    @Test
    fun `the newest come within the limit, steps only when asked`() {
        val page = EpisodeBuilder().build(listOf(back(1), back(2), back(3)), emptyList(), limit = 2, withSteps = false)
        assertEquals(3, page.total)
        assertEquals(2, page.episodes.size)
        assertEquals(1L, page.startWallMs)
        assertFalse(page.episodes.any { it.steps.isNotEmpty() })
    }

    @Test
    fun `names lose their package, not their arguments`() {
        assertEquals("ForecastKey(id=a.b)", EpisodeBuilder.simpleName("x.y.ForecastKey(id=a.b)"))
        assertEquals("Instrument", EpisodeBuilder.shortName("x.y.InstrumentRoute/{symbol}"))
    }
}
