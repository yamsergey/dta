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

/** Feature 017 (T011, T012): the Timeline tab's source — subscribe, page, follow, select, ask for causes. */
class TimelineControllerTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)
    private val timeline = TimelineController(context, scope)

    private fun ev(seq: Long, source: String, kind: String, wall: Long, vararg causes: Long) = TimelineEvent(
        "1:$seq", seq, wall, wall, source, kind,
        input = if (source == Sources.INPUT) InputPayload(InputAction.CLICK, text = "Delete", via = "view") else null,
        network = if (source == Sources.NETWORK) NetworkPayload("n$seq", NetworkPhase.REQUEST, "DELETE", path = "/items/42") else null,
        ui = if (source == Sources.UI) UiEvent("1:$seq", seq, wall, wall, UiEventKind.TOAST, UiSource.TOAST, text = "Deleted") else null,
        causes = causes.map { CauseLink("1:$it", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK) },
    )

    private val click = ev(1, Sources.INPUT, Kinds.CLICK, 1_000)
    private val request = ev(2, Sources.NETWORK, Kinds.NETWORK_REQUEST, 1_010, 1)
    private val toast = ev(3, Sources.UI, Kinds.ui("toast"), 1_500)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun await(check: (TimelineTabState) -> Boolean): TimelineTabState =
        runCatching { withTimeout(5_000) { timeline.state.first(check) } }.getOrElse { error("timed out; timeline=${timeline.state.value}") }

    @Test
    fun `everything kept is paged in after subscribing, then live events arrive`() = runBlocking<Unit> {
        daemon.ops.timeline.ingest(TimelineRead(1, (1..700L).map { ev(it, Sources.INPUT, Kinds.CLICK, 1_000 + it) }, lastSeq = 700))
        session.start()
        timeline.setActive(true)
        await { it.view.events.size == 700 && it.watching }
        daemon.ops.pushTimeline(ev(701, Sources.UI, Kinds.ui("toast"), 9_000))
        val s = await { it.view.events.size == 701 }
        assertEquals("1:701", s.view.events.last().id)
    }

    @Test
    fun `selecting asks for causes and effects, and the inferred toggle asks again`() = runBlocking<Unit> {
        daemon.ops.timeline.ingest(TimelineRead(1, listOf(click, request, toast), lastSeq = 3))
        session.start()
        timeline.setActive(true)
        await { it.view.events.size == 3 }
        timeline.select(request.id)
        val chosen = await { it.chain.causedBy.isNotEmpty() }
        assertEquals(listOf(click.id), chosen.chain.causedBy.map { it.eventId })
        timeline.select(click.id)
        assertEquals(listOf(request.id), await { it.view.selectedId == click.id && it.chain.ledTo.isNotEmpty() }.chain.ledTo.map { it.eventId })
        timeline.select(toast.id)
        val unknown = await { it.chain.noKnownCause != null }
        assertEquals("no known cause", unknown.chain.noKnownCause)
        timeline.setInferred(true)
        await { it.inferred && it.chainInferred }
    }

    @Test
    fun `an id the daemon no longer has is stated, not thrown`() = runBlocking<Unit> {
        daemon.ops.timeline.ingest(TimelineRead(1, listOf(click), lastSeq = 1))
        session.start()
        timeline.setActive(true)
        await { it.view.events.size == 1 }
        timeline.select("1:999")
        val s = await { it.chainError != null }
        assertTrue("1:999" in s.chainError!!, s.chainError)
    }

    @Test
    fun `the sources and current facets are read with the events`() = runBlocking<Unit> {
        daemon.ops.timeline.ingest(TimelineRead(1, listOf(click), lastSeq = 1, sources = listOf(io.yamsergey.dta.protocol.timeline.SourceStatus("nav", io.yamsergey.dta.protocol.ui.SourceState.OFF, "not used"))))
        session.start()
        timeline.setActive(true)
        val s = await { it.view.sources.isNotEmpty() }
        assertEquals("not used", s.view.sources.single().reason)
        assertNotNull(s.app)
        assertNull(s.error)
    }
}

/** Feature 065 (T017): Network's saved filters in the Timeline tab. */
class TimelineSavedFiltersTest {
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

    @Test
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

    /**
     * D-126: Show them then Hide them again asks twice; the first answer (opt-out on) can come after the second (off). It must not
     * be shown: it would say nothing is hidden while the tab says Hide them again.
     */
    @Test
    fun `a late answer to Show them does not undo Hide them again`() = runBlocking<Unit> {
        listShown()
        await { it.view.hiddenEntries.isNotEmpty() }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        daemon.ops.hiddenGate = { ignore -> if (ignore) release.await() }
        val answered = daemon.ops.hiddenAnswered.get()
        val asked = daemon.ops.hiddenAsks.get()
        timeline.setShowHidden(true)
        // The read of Show them is at the daemon (held there) before Hide them again is pressed.
        withTimeout(8_000) { while (daemon.ops.hiddenAsks.get() < asked + 1) kotlinx.coroutines.delay(10) }
        timeline.setShowHidden(false)
        withTimeout(8_000) { while (daemon.ops.hiddenAnswered.get() < answered + 1) kotlinx.coroutines.delay(10) }
        release.complete(Unit)
        withTimeout(8_000) { while (daemon.ops.hiddenAnswered.get() < answered + 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(500)
        val s = timeline.state.value
        assertEquals(false, s.showHidden)
        assertEquals(setOf("beacon"), s.view.hiddenEntries, "the answer of Hide them again stands")
        assertTrue(s.listSaved?.ignored != true, "not the answer of Show them: ${s.listSaved}")
    }

    @Test
    fun `a late sequence answer to Show them does not replace the one of Hide them again`() = runBlocking<Unit> {
        listShown()
        await { it.view.hiddenEntries.isNotEmpty() }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        daemon.ops.sequenceWhen = { ignore -> io.yamsergey.dta.protocol.timeline.SequencePage(emptyList(), if (ignore) 99 else 1, emptyList()) }
        daemon.ops.sequenceGate = { ignore -> if (ignore) release.await() }
        val answered = daemon.ops.sequenceAnswered.get()
        timeline.setShowHidden(true)
        withTimeout(8_000) { while (daemon.ops.sequenceIgnored.lastOrNull() != true) kotlinx.coroutines.delay(10) }
        timeline.setShowHidden(false)
        withTimeout(8_000) { while (daemon.ops.sequenceAnswered.get() < answered + 1) kotlinx.coroutines.delay(10) }
        release.complete(Unit)
        withTimeout(8_000) { while (daemon.ops.sequenceAnswered.get() < answered + 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(500)
        assertEquals(1, timeline.state.value.sequence.page?.total, "the answer of Hide them again stands")
    }
}
