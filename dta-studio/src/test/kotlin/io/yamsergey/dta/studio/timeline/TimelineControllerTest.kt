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
