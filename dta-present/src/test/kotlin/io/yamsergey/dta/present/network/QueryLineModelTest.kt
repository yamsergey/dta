package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.EntryHits
import io.yamsergey.dta.protocol.network.Fuzzy
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryLineModelTest {
    private fun line(t: String) = QueryLineModel(t)

    @Test
    fun `chips come from the line and removing one edits the line`() {
        val m = line("POST status:4xx  token -host:t.example.test")
        assertEquals(listOf("POST", "status:4xx", "-host:t.example.test"), m.chips.map { it.label })
        assertEquals("POST token -host:t.example.test", m.without(m.chips[1]).text)
        assertEquals(mapOf("q" to JsonPrimitive("POST status:4xx  token -host:t.example.test")), m.params())
        assertEquals(emptyMap(), line("  ").params())
    }

    @Test
    fun `the kind switch reads and writes the line`() {
        assertEquals(KindChoice.ALL, line("token").kind)
        assertEquals(KindChoice.WEBSOCKET, line("ws token").kind)
        assertEquals("http token", line("ws token").withKind(KindChoice.HTTP).text)
        assertEquals("token", line("kind:ws token").withKind(KindChoice.ALL).text)
        assertEquals("ws -host:x", line("-host:x").withKind(KindChoice.WEBSOCKET).text)
        assertEquals(null, line("kind:ws-message").kind)
    }

    @Test
    fun `suggestions for keys, values seen, and applying one`() {
        val seen = Seen(hosts = setOf("api.example.test", "cdn.example.test"), statuses = setOf("401"))
        assertEquals(listOf("status:", "since:"), line("s").suggestions(1).map { it.text })
        assertEquals(listOf("POST", "PUT", "PATCH").toSet(), line("P").suggestions(1).map { it.text }.toSet())
        assertEquals(listOf("status:4xx", "status:401"), line("status:4").suggestions(8, seen).map { it.text })
        assertEquals(listOf("-host:api.example.test"), line("token -host:a").suggestions(13, seen).map { it.text })
        val (applied, caret) = line("POST st").let { m -> m.apply(m.suggestions(7).first(), 7) }
        assertEquals("POST status:", applied.text)
        assertEquals(12, caret)
        val (done, c2) = applied.apply(applied.suggestions(12).first { it.text == "status:4xx" }, 12)
        assertEquals("POST status:4xx ", done.text)
        assertEquals(16, c2)
        assertTrue(line("").suggestions(0).isEmpty())
    }

    private fun hit(id: String, place: SearchPlace = SearchPlace.RESPONSE_BODY, part: BodyPart? = BodyPart.RESPONSE, header: String? = null, start: Int = 0) =
        SearchHit(id, place, "t", start, start + 1, Fuzzy.Excerpt("t", 0, 1), part, header)

    private fun entries(vararg ids: String) = ids.map { EntryHits(it, 0, listOf(hit(it))) }

    @Test
    fun `navigation wraps and keeps its place when hits refresh`() {
        var n = HitNavigator(entries("a", "b", "c"))
        assertEquals("– / 3", n.label)
        n = n.next()
        assertEquals("a", n.current!!.id)
        assertEquals("1 / 3", n.label)
        n = n.previous()
        assertEquals("c", n.current!!.id)
        n = n.refreshed(entries("new", "a", "b", "c"))
        assertEquals("c", n.current!!.id)
        assertEquals("4 / 4", n.label)
        // Its entry no longer a hit: the nearest in list order.
        n = n.refreshed(entries("a", "d"), order = listOf("a", "b", "c", "d"))
        assertEquals("d", n.current!!.id)
        assertEquals("No matches", HitNavigator().next().label)
        assertEquals("c", HitNavigator(entries("a", "b", "c")).previous().current!!.id)
    }

    @Test
    fun `highlights and the tab of a hit`() {
        val hits = listOf(hit("a", start = 5), hit("a", start = 1), hit("a", SearchPlace.REQUEST_HEADER, null, "X-Order-Id"), hit("a", SearchPlace.URL, null))
        assertEquals(listOf(1..1, 5..5), Highlights.body(hits, BodyPart.RESPONSE))
        assertEquals(setOf("x-order-id"), Highlights.headers(hits, response = false))
        assertEquals(HitTab.RESPONSE, HitNavigator().tabOf(hits))
        assertEquals(HitTab.HEADERS, HitNavigator().tabOf(hits.drop(2)))
    }
}
