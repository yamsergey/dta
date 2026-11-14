package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.dialog
import io.yamsergey.dta.present.timeline.TimelineFixture.navigate
import io.yamsergey.dta.present.timeline.TimelineFixture.removed
import io.yamsergey.dta.present.timeline.TimelineFixture.response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 017 (T004): what an event was, by source. */
class EventDetailTest {
    private fun rows(d: EventDetail) = d.rows.associate { it.label to it.value }

    @Test
    fun `a window shows its kind, title and texts`() {
        val d = EventDetail.of(dialog)
        assertEquals("dialog appeared", d.title)
        assertEquals("Deleting…", rows(d)["title"])
        assertEquals("Deleting… · Cancel", rows(d)["content"])
    }

    @Test
    fun `a response shows the request line, status, duration and its entry`() {
        val r = rows(EventDetail.of(response))
        assertEquals("DELETE api.example.com/items/42", r["request"])
        assertEquals("200", r["status"])
        assertEquals("408 ms", r["duration"])
        assertEquals("n7", r["entry"])
        assertEquals("n7", EventDetail.of(response).networkEntry)
    }

    @Test
    fun `a click, a navigation and an app event`() {
        assertEquals("delete_button|Button", rows(EventDetail.of(click))["element"])
        assertEquals("Delete", rows(EventDetail.of(click))["text"])
        val nav = rows(EventDetail.of(navigate))
        assertEquals("cart", nav["destination"])
        assertEquals("from=menu", nav["arguments"])
        val app = EventDetail.of(removed)
        assertEquals("42", rows(app)["id"])
        assertTrue(app.rows.any { it.label == "app.cart" && it.value == "items → empty" })
    }

    private val chain = listOf(
        io.yamsergey.dta.protocol.layout.Frame("MarketsScreen", "BrokerNav.kt", 85, library = false),
        io.yamsergey.dta.protocol.layout.Frame("ListItem", "BrokerNav.kt", 103, library = false),
    )
    private val tap = click.copy(input = click.input!!.copy(via = "compose", chain = chain, chainMainThreadMs = 6))

    @Test
    fun `a compose click lists its chain root to node, with the read's time (064)`() {
        val d = EventDetail.of(tap)
        assertEquals(chain, d.chain)
        assertEquals(null, rows(d)["chain"], "the chain is its own section, as Layout's Call chain")
        assertEquals(listOf("BrokerNav.kt"), d.callGroups.map { it.file })
        assertEquals("MarketsScreen › ListItem", d.callGroups.single().names)
        assertEquals("6 ms on the main thread", rows(d)["chain read"])
        assertEquals(emptyList(), EventDetail.of(click).chain)
    }
}
