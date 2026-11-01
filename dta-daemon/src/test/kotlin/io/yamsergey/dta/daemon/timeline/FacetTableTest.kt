package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Feature 016 (T012): the current value of every facet on the host, and when it went stale. */
class FacetTableTest {
    private val table = FacetTable()

    private fun event(id: String, mono: Long, vararg changes: FacetChange) =
        TimelineEvent(id, 1, mono, 1_000 + mono, Sources.APP, "app/x", facets = changes.toList())

    @Test
    fun `the snapshot sets values and changes update value, moment and cause`() {
        table.seed(7, listOf(FacetValue("ui.windows", "MainActivity", Sources.UI, 5, 1_005, "7:1")))
        table.apply(event("7:4", 40, FacetChange("app.cart", "items"), FacetChange("ui.windows", "MainActivity + dialog")))
        val state = table.current(emptyList()).facets.associateBy { it.name }
        assertEquals("items", state["app.cart"]!!.value)
        assertEquals(Sources.APP, state["app.cart"]!!.source)
        assertEquals("7:4", state["ui.windows"]!!.changedBy)
        assertEquals(40, state["ui.windows"]!!.changedMonoMs)
    }

    @Test
    fun `a process end makes every facet stale with the reason`() {
        table.seed(7, listOf(FacetValue("app.cart", "items", Sources.APP, 5, 1_005)))
        table.processEnded(2_000)
        val stale = table.current(emptyList()).facets.single().stale
        assertNotNull(stale)
        assertEquals("process-ended", stale.reason)
    }

    @Test
    fun `a new process keeps the old facets stale until its own snapshot arrives`() {
        table.seed(7, listOf(FacetValue("app.cart", "items", Sources.APP, 5, 1_005)))
        table.processEnded(2_000)
        table.seed(8, emptyList())
        assertEquals("process-ended", table.current(emptyList()).facets.single().stale?.reason)
        table.seed(8, listOf(FacetValue("app.cart", "empty", Sources.APP, 1, 3_000)))
        val fresh = table.current(emptyList()).facets.single()
        assertEquals("empty", fresh.value)
        assertNull(fresh.stale)
    }

    @Test
    fun `facets of a source that is off are stale, and statuses come with the state`() {
        table.seed(7, listOf(FacetValue("nav.destination", "cart", Sources.NAV, 5, 1_005)))
        val off = SourceStatus(Sources.NAV, SourceState.OFF, "hook failed")
        val state = table.current(listOf(off))
        assertEquals("source off: hook failed", state.facets.single().stale?.reason)
        assertEquals(listOf(off), state.sources)
    }

    @Test
    fun `a cleared facet is dropped from the current state`() {
        table.apply(event("7:1", 1, FacetChange("app.auth", "signed-in")))
        table.apply(event("7:2", 2, FacetChange("app.auth", null, previous = "signed-in")))
        assertEquals(emptyList(), table.current(emptyList()).facets)
    }
}
