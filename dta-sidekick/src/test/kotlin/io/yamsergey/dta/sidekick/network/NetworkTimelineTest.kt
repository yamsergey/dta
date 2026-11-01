package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 016 (T020): network exchanges in the timeline, caused by what was handled when they were issued. */
class NetworkTimelineTest {
    private val events = mutableListOf<TimelineEvent>()
    private val buffer = TimelineBuffer(epoch = 7) { events += it }
    private var now = 100L
    private val network = NetworkTimeline(buffer, mono = { now })

    private val click = CauseLink("7:1", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)

    private fun entry(state: HttpState, cause: CauseLink? = click) = HttpEntry(
        id = "n7", runId = "r", timeMs = 1_000, library = "okhttp3",
        initiator = Initiator(thread = "main", site = InitiatorSite.ENQUEUE, missing = "test", cause = cause),
        method = "DELETE", url = "https://api.example.com/items/42?token=secret", state = state,
        status = if (state == HttpState.COMPLETE) 200 else null, durationMs = if (state == HttpState.PENDING) null else 408,
        failure = if (state == HttpState.FAILED) "timeout" else null,
    )

    @Test
    fun `a request carries the cause remembered when it was issued, and its response links to it`() {
        network.observe(entry(HttpState.PENDING))
        network.observe(entry(HttpState.PENDING))
        now = 520
        network.observe(entry(HttpState.COMPLETE))
        network.observe(entry(HttpState.COMPLETE))
        assertEquals(listOf(Kinds.NETWORK_REQUEST, Kinds.NETWORK_RESPONSE), events.map { it.kind })
        val (request, response) = events
        assertEquals(listOf(click), request.causes)
        assertEquals(NetworkPhase.REQUEST, request.network!!.phase)
        assertEquals("/items/42", request.network!!.path, "no query values")
        assertEquals("api.example.com", request.network!!.host)
        assertEquals(CauseLink(request.id, Basis.EXACT, CauseRules.SAME_EXCHANGE), response.causes.single())
        assertEquals(200, response.network!!.status)
        assertEquals(520, response.monoMs)
        assertTrue("token" !in request.title + response.title)
    }

    @Test
    fun `an exchange seen only when it ended still gets both events, and a failure says why`() {
        network.observe(entry(HttpState.FAILED, cause = null))
        assertEquals(listOf(Kinds.NETWORK_REQUEST, Kinds.NETWORK_FAILED), events.map { it.kind })
        assertTrue(events.first().causes.isEmpty())
        assertEquals("timeout", events.last().network!!.error)
    }

    @Test
    fun `entries that are not http exchanges are left out`() {
        network.observe(entry(HttpState.PENDING).copy(state = HttpState.PROCESS_ENDED))
        assertNull(events.firstOrNull { it.kind == Kinds.NETWORK_RESPONSE })
    }
}
