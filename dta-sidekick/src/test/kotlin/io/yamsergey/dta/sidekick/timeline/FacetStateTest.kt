package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.timeline.FacetChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 016 (T007): the facets the sidekick owns — changes only when the value differs, cut at the bound. */
class FacetStateTest {
    private var now = 10L
    private val facets = FacetState(clock = { now }, wall = { now + 1_000 })

    @Test
    fun `a change is reported only when the value differs`() {
        assertEquals(FacetChange("app.cart", "items"), facets.change("app.cart", "items", "e1"))
        assertNull(facets.change("app.cart", "items", "e2"))
        assertEquals(FacetChange("app.cart", "empty", previous = "items"), facets.change("app.cart", "empty", "e3"))
        assertEquals(FacetChange("app.cart", null, previous = "empty"), facets.change("app.cart", null, "e4"))
    }

    @Test
    fun `long values are cut and say so`() {
        val change = facets.change("app.long", "x".repeat(500), null)!!
        assertEquals(Bounds.FACET_VALUE, change.value!!.length)
        assertTrue(change.cut)
        assertNull(facets.change("app.long", "x".repeat(400), null), "the same after the cut")
    }

    @Test
    fun `the snapshot lists every value with its source, moment and the event that set it`() {
        facets.change("ui.windows", "MainActivity", "e1")
        now = 20
        facets.change("app.cart", "items", "e2")
        val snapshot = facets.snapshot().associateBy { it.name }
        assertEquals("ui", snapshot["ui.windows"]!!.source)
        assertEquals(10, snapshot["ui.windows"]!!.changedMonoMs)
        assertEquals(1_020, snapshot["app.cart"]!!.changedWallMs)
        assertEquals("e2", snapshot["app.cart"]!!.changedBy)
    }
}
