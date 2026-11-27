package io.yamsergey.dta.studio.timeline

import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelineRead
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmpRepeatSavedFiltersTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)
    private val timeline = TimelineController(context, scope)

    private fun ev(seq: Long, source: String, wall: Long, entry: String? = null) = TimelineEvent(
        "1:$seq", seq, wall, wall, source, if (source == Sources.NETWORK) Kinds.NETWORK_REQUEST else Kinds.CLICK,
        input = if (source == Sources.INPUT) InputPayload(InputAction.CLICK, text = "Buy", via = "compose") else null,
        network = entry?.let { NetworkPayload(it, NetworkPhase.REQUEST, "POST", host = "telemetry.example.test", path = "/v1/events") },
    )

    private val events = listOf(ev(1, Sources.INPUT, 1_000), ev(2, Sources.NETWORK, 1_010, "beacon"), ev(3, Sources.NETWORK, 1_020, "order"))
    private val words = listOf("-host:telemetry.example.test")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun await(check: (TimelineTabState) -> Boolean): TimelineTabState =
        runCatching { withTimeout(8_000) { timeline.state.first(check) } }.getOrElse { error("timed out; timeline=${timeline.state.value}") }

    private suspend fun listShown() {
        daemon.ops.timeline.ingest(TimelineRead(1, events, lastSeq = 3))
        daemon.ops.hidden = io.yamsergey.dta.protocol.timeline.TimelineHidden(
            io.yamsergey.dta.protocol.network.SavedFiltersApplied(words, hidden = 1), hidden = listOf("beacon"), notJudged = listOf("order"),
        )
        session.start()
        timeline.setShowSequence(false)
        timeline.setActive(true)
    }

    @Test
    fun `the list keeps every event, hides what timeline hidden names, and counts its rows`() = runBlocking<Unit> {
        listShown()
        val s = await { it.view.events.size == 3 && it.view.hiddenEntries.isNotEmpty() }
        assertEquals(listOf("1:1", "1:3"), s.view.shown.map { it.id })
        assertTrue(daemon.ops.timelineIgnored.isNotEmpty() && daemon.ops.timelineIgnored.all { it }, "pages are read whole: the list hides by itself")
        assertEquals(listOf(true), daemon.ops.watchIgnored.toList(), "and so are live events")
        val bar = s.listBar
        assertEquals(words, bar.chips)
        assertEquals("1 network row hidden by Network's filters · 1 not judged: its request is no longer kept", bar.caption)
        assertTrue(s.view.notJudged(events[2]))
    }

    @org.junit.jupiter.api.RepeatedTest(400)
    fun `Show them shows everything and asks with the opt-out, Hide them again hides again`() = runBlocking<Unit> {
        listShown()
        await { it.view.hiddenEntries.isNotEmpty() }
        timeline.setShowHidden(true)
        val shown = await { it.showHidden && it.view.shown.size == 3 }
        assertEquals(io.yamsergey.dta.present.timeline.TimelineSavedBar.Action.HIDE, shown.listBar.action)
        timeline.setShowSequence(true)
        await { daemon.ops.sequenceIgnored.lastOrNull() == true }
        timeline.setShowHidden(false)
        await { !it.showHidden && daemon.ops.sequenceIgnored.lastOrNull() == false }
    }

    @Test
    fun `x on a chip removes the filter from Network's saved set`() = runBlocking<Unit> {
        daemon.ops.savedWords += words
        listShown()
        await { it.view.events.size == 3 }
        timeline.removeSaved(words.single())
        await { daemon.ops.savedWords.isEmpty() && it.savedError == null }
    }
}
