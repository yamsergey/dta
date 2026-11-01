package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 016 (T041): states and transitions from facet changes, regrouped by the facets chosen.
 * The session: catalog → product → cart (items) → delete (still items) → delete (empty) → back → back.
 */
class StateGrouperTest {
    private var seq = 0L
    private val events = mutableListOf<TimelineEvent>()

    private fun click(mono: Long) = TimelineEvent("7:${++seq}", seq, mono, mono, Sources.INPUT, Kinds.CLICK, input = InputPayload(InputAction.CLICK, via = "view")).also { events += it }

    private fun nav(mono: Long, to: String, from: String, cause: TimelineEvent?) = TimelineEvent(
        "7:${++seq}", seq, mono, mono, Sources.NAV, Kinds.NAV_NAVIGATE,
        facets = listOf(FacetChange("nav.destination", to, from)),
        causes = listOfNotNull(cause?.let { CauseLink(it.id, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK) }),
    ).also { events += it }

    private fun cart(mono: Long, value: String, previous: String?, cause: TimelineEvent?) = TimelineEvent(
        "7:${++seq}", seq, mono, mono, Sources.APP, Kinds.app("cart.removed"),
        facets = if (value == previous) emptyList() else listOf(FacetChange("app.cart", value, previous)),
        causes = listOfNotNull(cause?.let { CauseLink(it.id, Basis.EXACT, CauseRules.ISSUED_DURING_CLICK) }),
    ).also { events += it }

    private val start = listOf(
        FacetValue("nav.destination", "catalog", Sources.NAV, 0, 0),
        FacetValue("app.cart", "items", Sources.APP, 0, 0),
        FacetValue("ui.windows", "MainActivity", Sources.UI, 0, 0),
    )

    init {
        nav(100, "product", "catalog", click(90))
        nav(200, "cart", "product", click(190))
        cart(300, "items", "items", click(290))
        cart(400, "empty", "items", click(390))
        nav(500, "product", "cart", null)
        nav(600, "catalog", "product", click(590))
    }

    private fun group(facets: List<String>?, minHoldMs: Long = 0) =
        StateGrouper(events, initial = start, available = start.map { it.name }.toSet() + "nav.args").graph(facets, minHoldMs = minHoldMs)

    @Test
    fun `one facet gives coarse states`() {
        val g = group(listOf("app.cart"))
        assertEquals(setOf("items", "empty"), g.nodes.map { it.state.label }.toSet())
        assertEquals(1, g.transitions.count { !it.selfLoop })
        assertEquals("empty", g.nodes.single { it.current }.state.label)
    }

    @Test
    fun `two facets give finer states`() {
        val g = group(listOf("nav.destination", "app.cart"))
        assertEquals(
            setOf("catalog · items", "product · items", "cart · items", "cart · empty", "product · empty", "catalog · empty"),
            g.nodes.map { it.state.label }.toSet(),
        )
        val back = g.transitions.single { t -> label(g, t.from) == "cart · empty" && label(g, t.to) == "product · empty" }
        assertTrue(back.noKnownCause)
        val forward = g.transitions.single { t -> label(g, t.from) == "catalog · items" }
        assertEquals(Basis.EXACT, forward.causes.single().basis)
        assertTrue(!forward.noKnownCause)
    }

    @Test
    fun `an app event that changes no chosen value is a self-loop`() {
        val g = group(listOf("app.cart"))
        val loop = g.transitions.single { it.selfLoop }
        assertEquals(loop.from, loop.to)
        assertEquals(1, loop.count)
    }

    @Test
    fun `entries and time held`() {
        val g = group(listOf("nav.destination"))
        val product = g.nodes.single { it.state.label == "product" }
        assertEquals(2, product.entries)
        assertEquals(100 + 100, product.heldMs)
    }

    @Test
    fun `short states are merged into the next one, and that is stated`() {
        val g = group(listOf("nav.destination"), minHoldMs = 150)
        assertTrue(g.nodes.none { it.state.label == "product" })
        assertTrue(g.merged > 0)
        assertTrue(g.notes.any { it.reason.startsWith("state-short-merged") })
    }

    @Test
    fun `an unavailable facet is stated and left out`() {
        val g = group(listOf("app.cart", "nav.key"))
        assertEquals(listOf("nav.key"), g.unavailable)
        assertEquals(listOf("app.cart"), g.facets)
        assertTrue(g.notes.any { it.reason.startsWith("state-facet-unavailable") })
    }

    @Test
    fun `the default facets leave out arguments and open windows`() {
        assertEquals(listOf("app.cart", "nav.destination"), group(null).facets)
    }

    @Test
    fun `a partial window is stated`() {
        val g = StateGrouper(events, start, start.map { it.name }.toSet(), partial = true).graph(listOf("app.cart"))
        assertTrue(g.window.partial)
        assertTrue(g.notes.any { it.reason.startsWith("state-window-partial") })
    }

    @Test
    fun `the shortest observed path`() {
        val g = group(listOf("nav.destination"))
        val path = StatePaths(g).path(from = "catalog", to = "cart")
        assertTrue(path.found)
        assertEquals(listOf("catalog", "product", "cart"), listOf(label(g, path.steps.first().from)) + path.steps.map { label(g, it.to) })
        val none = StatePaths(g).path(from = "cart", to = "nowhere")
        assertTrue(!none.found)
        assertEquals("catalog", label(g, StatePaths(g).path(from = "cart").steps.last().to), "to the current state by default")
    }

    private fun label(g: io.yamsergey.dta.protocol.timeline.StateGraph, id: String) = g.nodes.single { it.state.id == id }.state.label
}
