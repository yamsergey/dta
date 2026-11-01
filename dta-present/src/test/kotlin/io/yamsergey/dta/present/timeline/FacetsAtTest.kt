package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.flow
import io.yamsergey.dta.present.timeline.TimelineFixture.navigate
import io.yamsergey.dta.present.timeline.TimelineFixture.removed
import io.yamsergey.dta.protocol.timeline.FacetValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 017 (T003): what the app was in at an event's moment. */
class FacetsAtTest {
    private val current = listOf(
        FacetValue("app.cart", "empty", "app", 560, 1_560),
        FacetValue("nav.destination", "cart", "nav", 600, 1_600),
        FacetValue("ui.windows", "MainActivity", "ui", 10, 1_010),
    )

    private fun at(id: String, events: List<io.yamsergey.dta.protocol.timeline.TimelineEvent> = flow) =
        FacetsAt.of(events, current, id)

    @Test
    fun `before a facet's first change it had the value that change replaced`() {
        val v = at(click.id).values.associate { it.name to it.value }
        assertEquals(mapOf("app.cart" to "items", "nav.destination" to "catalog", "ui.windows" to "MainActivity"), v)
    }

    @Test
    fun `changes apply up to the selected event`() {
        assertEquals("empty", at(removed.id).values.single { it.name == "app.cart" }.value)
        assertEquals("catalog", at(removed.id).values.single { it.name == "nav.destination" }.value)
        assertEquals("cart", at(navigate.id).values.single { it.name == "nav.destination" }.value)
    }

    @Test
    fun `a run whose first events are gone is partial`() {
        assertFalse(at(click.id).partial)
        assertTrue(at(removed.id, flow.drop(2)).partial)
    }

    @Test
    fun `an event of an earlier run does not take today's values`() {
        val old = click.copy(id = "6:1", seq = 1)
        assertTrue(FacetsAt.of(listOf(old) + flow, current, old.id).values.isEmpty())
    }
}
