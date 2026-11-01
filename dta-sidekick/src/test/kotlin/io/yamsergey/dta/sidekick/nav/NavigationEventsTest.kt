package io.yamsergey.dta.sidekick.nav

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 016 (T046): destinations and back stack keys as navigation events and facets. */
class NavigationEventsTest {
    private val events = mutableListOf<TimelineEvent>()
    private val buffer = TimelineBuffer(epoch = 7) { events += it }
    private val facets = FacetState(clock = { 1 }, wall = { 1 })
    private var cause: CauseLink? = null
    private val nav = NavigationEvents(buffer, facets, mono = { 5 }, wall = { 6 }, scope = { cause })

    @Test
    fun `a deeper stack is a navigate, a shallower one a back, with facets`() {
        nav.destination("navigation", "catalog", emptyMap(), depth = 1)
        cause = CauseLink("7:1", Basis.EXACT, CauseRules.ISSUED_DURING_CLICK)
        nav.destination("navigation", "product/{id}", mapOf("id" to "42"), depth = 2)
        cause = null
        nav.destination("navigation", "catalog", emptyMap(), depth = 1)
        assertEquals(listOf(Kinds.NAV_NAVIGATE, Kinds.NAV_NAVIGATE, Kinds.NAV_BACK), events.map { it.kind })
        val product = events[1]
        assertEquals(Sources.NAV, product.source)
        assertEquals("product/{id}", product.nav!!.destination)
        assertEquals(mapOf("id" to "42"), product.nav!!.args)
        assertEquals(listOf(Facets.NAV_DESTINATION, Facets.NAV_ARGS), product.facets.map { it.name })
        assertEquals("id=42", product.facets.last().value)
        assertEquals("7:1", product.causes.single().from)
        assertEquals("navigate → product/{id} id=42", product.title)
    }

    @Test
    fun `the same destination again is not a new event`() {
        nav.destination("navigation", "catalog", emptyMap(), depth = 1)
        nav.destination("navigation", "catalog", emptyMap(), depth = 1)
        assertEquals(1, events.size)
    }

    @Test
    fun `arguments are bounded and only short values are kept`() {
        val args = NavigationEvents.args((1..20).associate { "k$it" to "v".repeat(300) })
        assertEquals(8, args.size)
        assertTrue(args.values.all { it.length <= 120 })
        assertEquals("Product", NavigationEvents.keyName(Any(), fallback = "Product"))
        assertEquals("ProductKey(id=42)", NavigationEvents.keyName(ProductKey(42), fallback = "ProductKey"))
    }

    @Test
    fun `a navigation 3 key is its own facet`() {
        nav.key("ProductKey(id=42)", depth = 2)
        nav.key("ProductKey(id=42)", depth = 2)
        nav.key("CartKey", depth = 3)
        assertEquals(2, events.size)
        assertEquals(Facets.NAV_KEY, events.last().facets.single().name)
        assertEquals("navigation3", events.last().nav!!.library)
    }

    private data class ProductKey(val id: Int)
}
