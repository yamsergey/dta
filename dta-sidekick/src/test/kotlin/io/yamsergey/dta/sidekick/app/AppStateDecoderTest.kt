package io.yamsergey.dta.sidekick.app

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.sidekick.timeline.HandlingScope
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 016 (T037): the app's calls through the bridge, as timeline events. */
class AppStateDecoderTest {
    private val events = mutableListOf<TimelineEvent>()
    private val buffer = TimelineBuffer(epoch = 7) { events += it }
    private var now = 100L
    private val facets = FacetState(clock = { now }, wall = { now })
    private val scope = HandlingScope(isMain = { true }, clock = { now })
    private val decoder = AppStateDecoder(buffer, facets, scope, mono = { now }, wall = { now }, rate = TokenBucket(perSecond = 3, clock = { now }))

    @Test
    fun `a facet change is an event carrying it, only when the value differs`() {
        decoder.emit(1, "cart", "items", null, 0, 0)
        decoder.emit(1, "cart", "items", null, 0, 0)
        val e = events.single()
        assertEquals(Sources.APP, e.source)
        assertEquals(Kinds.FACET_CHANGED, e.kind)
        assertEquals("app.cart", e.facets.single().name)
        assertEquals("app.cart = items", e.title)
    }

    @Test
    fun `an event with attributes and a declared cause from an earlier one`() {
        decoder.emit(2, "cart.removed", null, arrayOf("id", "42"), 1, 0)
        decoder.emit(2, "cart.synced", null, null, 2, 1)
        val (removed, synced) = events
        assertEquals(Kinds.app("cart.removed"), removed.kind)
        assertEquals(mapOf("id" to "42"), removed.app!!.attrs)
        val cause = synced.causes.single()
        assertEquals(removed.id, cause.from)
        assertEquals(Basis.DECLARED, cause.basis)
        assertEquals(CauseRules.APP_DECLARED, cause.rule)
        assertEquals(Sources.APP, cause.declaredBy)
    }

    @Test
    fun `a cause to an event DTA never saw keeps its far end as unknown`() {
        decoder.emit(2, "late", null, null, 5, 99)
        assertTrue(events.single().causes.single().fromUnknown)
        assertEquals("local:99", events.single().causes.single().from)
    }

    @Test
    fun `a cause declared afterwards names its effect`() {
        decoder.emit(2, "a", null, null, 1, 0)
        decoder.emit(2, "b", null, null, 2, 0)
        decoder.emit(3, null, null, null, 2, 1)
        val declared = events.last()
        assertEquals(Kinds.DECLARED_CAUSE, declared.kind)
        assertEquals(events[1].id, declared.app!!.attrs["to"])
        assertEquals(events[0].id, declared.causes.single().from)
    }

    @Test
    fun `inside an app scope what happens is caused by the scope's event, declared`() {
        decoder.emit(2, "sync", null, null, 1, 0)
        decoder.emit(4, null, null, null, 1, 0)
        val inside = scope.current()!!
        assertEquals(events.single().id, inside.from)
        assertEquals(Basis.DECLARED, inside.basis)
        decoder.emit(5, null, null, null, 1, 0)
        assertEquals(null, scope.current())
    }

    @Test
    fun `an event inside a click scope is caused by the click as well`() {
        scope.enter("7:99", Kinds.CLICK)
        decoder.emit(2, "cart.removed", null, null, 1, 0)
        assertEquals("7:99", events.single().causes.single().from)
    }

    @Test
    fun `beyond the rate the excess is dropped, then stated as a gap`() {
        repeat(5) { decoder.emit(2, "spam", null, null, it + 1L, 0) }
        assertEquals(3, events.size)
        now += 1_000
        decoder.emit(2, "after", null, null, 10, 0)
        val gap = events[3]
        assertEquals(Kinds.GAP, gap.kind)
        assertEquals(2, gap.gapCount)
        assertEquals(Sources.APP, gap.gapSource)
        assertTrue(gap.completeness.single().reason.startsWith("timeline-rate-dropped"))
        assertEquals(Kinds.app("after"), events[4].kind)
    }

    @Test
    fun `odd attribute arrays and unknown ops do no harm`() {
        decoder.emit(2, "odd", null, arrayOf("lonely"), 1, 0)
        decoder.emit(42, "x", null, null, 0, 0)
        assertEquals(emptyMap(), events.single().app!!.attrs)
    }
}
