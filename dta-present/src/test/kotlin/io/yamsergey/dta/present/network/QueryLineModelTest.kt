package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.EntryHits
import io.yamsergey.dta.protocol.network.Fuzzy
import io.yamsergey.dta.protocol.network.RecentFilter
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryLineModelTest {
    private fun line(t: String) = QueryLineModel(t)

    @Test
    fun `Enter takes the filter words out of the line (043)`() {
        val c = line("POST status:4xx  token -host:t.example.test").commit()
        assertEquals(listOf("POST", "status:4xx", "-host:t.example.test"), c.words)
        assertEquals("token", c.rest)
        assertEquals(mapOf("q" to JsonPrimitive("token")), line("token").params())
        assertEquals(emptyMap(), line("  ").params())
    }

    @Test
    fun `the kind switch joins the query, a kind typed in the line wins (043)`() {
        assertEquals(mapOf("q" to JsonPrimitive("ws token")), line("token").params(KindChoice.WEBSOCKET))
        assertEquals(mapOf("q" to JsonPrimitive("http")), line("").params(KindChoice.HTTP))
        assertEquals(mapOf("q" to JsonPrimitive("kind:ws-message")), line("kind:ws-message").params(KindChoice.HTTP))
        assertEquals(emptyMap(), line("").params(KindChoice.ALL))
    }

    @Test
    fun `an empty line offers the app's recent filters, a typed word those that start with it (043)`() {
        val recent = listOf(RecentFilter("-host:t.example.test", 1000), RecentFilter("-url:/health", 900))
        val now = 1000L + 5 * 60_000
        assertEquals(
            listOf(Suggestion("-host:t.example.test", 0, "5 min ago", recent = true), Suggestion("-url:/health", 0, "5 min ago", recent = true)),
            line("").suggestions(0, recent = recent, nowMs = now).filter { it.recent },
        )
        assertEquals(listOf("-url:/health"), line("-u").suggestions(2, recent = recent, nowMs = now).filter { it.recent }.map { it.text })
    }

    @Test
    fun `suggestions for keys, values seen, and applying one`() {
        val seen = Seen(hosts = setOf("api.example.test", "cdn.example.test"), statuses = setOf("401"))
        assertEquals(listOf("status:", "size:", "since:"), line("s").suggestions(1).map { it.text })
        assertEquals(listOf("POST", "PUT", "PATCH").toSet(), line("P").suggestions(1).map { it.text }.toSet())
        assertEquals(listOf("status:4xx", "status:401"), line("status:4").suggestions(8, seen).map { it.text })
        assertEquals(listOf("-host:api.example.test"), line("token -host:a").suggestions(13, seen).map { it.text })
        val (applied, caret) = line("POST st").let { m -> m.apply(m.suggestions(7).first(), 7) }
        assertEquals("POST status:", applied.text)
        assertEquals(12, caret)
        val (done, c2) = applied.apply(applied.suggestions(12).first { it.text == "status:4xx" }, 12)
        assertEquals("POST status:4xx ", done.text)
        assertEquals(16, c2)
        assertTrue(line("token ").suggestions(6).isEmpty(), "a space after a search word stays quiet")
    }

    @Test
    fun `an empty line lists every key with its hint, recent filters first (051)`() {
        val recent = listOf(RecentFilter("-host:t.example.test", 1000))
        val all = line("").suggestions(0, recent = recent, nowMs = 2000)
        assertEquals("-host:t.example.test", all.first().text)
        val keys = all.filter { !it.recent }
        assertEquals(io.yamsergey.dta.protocol.network.QueryLine.KEYS.map { "$it:" }, keys.map { it.text })
        assertTrue(keys.all { !it.hint.isNullOrBlank() })
        assertEquals("body:", keys.first().text)
        assertTrue(keys.first().hint!!.contains("case ignored"))
        // Down mid-line asks for the list too.
        assertEquals(keys.map { it.text }, line("token ").suggestions(6, explicit = true).map { it.text })
    }

    @Test
    fun `the kind switch puts its keys first (051)`() {
        val ws = line("").suggestions(0, kind = KindChoice.WEBSOCKET).map { it.text.removeSuffix(":") }
        val firstHttpOnly = ws.indexOf("method")
        listOf("dir", "frame", "type", "topic", "conn").forEach { assertTrue(ws.indexOf(it) < firstHttpOnly, it) }
        val http = line("").suggestions(0, kind = KindChoice.HTTP).map { it.text.removeSuffix(":") }
        listOf("method", "header", "req", "resp", "duration").forEach { assertTrue(http.indexOf(it) < http.indexOf("dir"), it) }
        assertEquals("bo", "bo")
        assertEquals(listOf("body:"), line("bo").suggestions(2).map { it.text })
        assertTrue(line("bo").suggestions(2).single().hint!!.isNotBlank())
    }

    @Test
    fun `values for the new keys (051)`() {
        assertEquals(listOf("dir:sent", "dir:received"), line("dir:").suggestions(4).map { it.text })
        assertEquals(listOf("frame:text", "frame:binary"), line("frame:").suggestions(6).map { it.text })
        assertTrue(line("status:").suggestions(7).map { it.text }.containsAll(listOf("status:4xx", "status:open", "status:closed", "status:1006")))
        assertTrue(line("mime:").suggestions(5).map { it.text }.contains("mime:json"))
        assertTrue(line("duration:").suggestions(9).map { it.text }.contains("duration:>1s"))
        assertTrue(line("size:").suggestions(5).map { it.text }.contains("size:>100k"))
        assertEquals(listOf("-dir:sent", "-dir:received"), line("-dir:").suggestions(5).map { it.text })
    }

    @Test
    fun `body keys offer the pattern form (053)`() {
        val s = line("body:").suggestions(5)
        assertEquals("body:/", s.first().text)
        assertTrue(s.first().hint!!.contains("regex"))
        assertEquals(listOf("-resp:/"), line("-resp:").suggestions(6).map { it.text })
        assertEquals(listOf("req:/"), line("req:").suggestions(4).map { it.text })
        val (applied, caret) = line("GET body:").let { m -> m.apply(m.suggestions(9).first(), 9) }
        assertEquals("GET body:/", applied.text)
        assertEquals(10, caret)
        assertEquals(emptyList<String>(), line("body:/a").suggestions(7).map { it.text })
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

    @Test
    fun `spans of the words in a text as shown`() {
        val pretty = "{\n  \"error\": \"Token expired\",\n  \"order_id\": 7\n}"
        assertEquals(listOf("Token expired", "order_id"), Highlights.spans(pretty, listOf("token expired", "orderid")).map { pretty.substring(it) })
    }

    @Test
    fun `found in lists the places with counts, in tab order`() {
        fun hit(place: SearchPlace, header: String? = null) = SearchHit("r1:1", place, "t", 0, 1, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("t", 0, 1), header = header)
        val found = Hits.foundIn(listOf(hit(SearchPlace.RESPONSE_BODY), hit(SearchPlace.URL), hit(SearchPlace.RESPONSE_BODY), hit(SearchPlace.REQUEST_HEADER, "Authorization"), hit(SearchPlace.RESPONSE_BODY)))
        assertEquals(listOf("URL", "request header Authorization", "response body ×3"), found.map { it.label })
        assertEquals(listOf(HitTab.HEADERS, HitTab.HEADERS, HitTab.RESPONSE), found.map { it.tab })
    }

}
