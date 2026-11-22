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
        assertEquals(listOf("status:", "size:", "source:", "since:"), line("s").suggestions(1).map { it.text })
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
    fun `a value that is also a recent filter is offered once, as the recent one (063)`() {
        val recent = listOf(RecentFilter("status:4xx", 1000))
        val texts = line("status:").suggestions(7, recent = recent, nowMs = 2000).map { it.text }
        assertEquals(1, texts.count { it == "status:4xx" })
        assertEquals("status:4xx", texts.first())
    }

    @Test
    fun `what the line or the saved filters already hold is not offered again`() {
        val seen = Seen(hosts = setOf("api.example.test", "cdn.example.test"))
        // A value already in the line, typed again: not offered.
        assertEquals(listOf("-host:cdn.example.test"), line("-host:api.example.test -host:").suggestions(29, seen).map { it.text })
        // A saved filter (a chip) is not offered as a value or as a recent one.
        val saved = listOf("-host:api.example.test", "status:4xx")
        assertEquals(listOf("-host:cdn.example.test"), line("-host:").suggestions(6, seen, saved = saved).map { it.text })
        val recent = listOf(RecentFilter("-host:api.example.test", 1000), RecentFilter("-url:/health", 900))
        assertEquals(listOf("-url:/health"), line("").suggestions(0, recent = recent, saved = saved, nowMs = 2000).filter { it.recent }.map { it.text })
        // A key that takes one value, already given in the line: neither the key nor its values (the line would refuse it).
        assertTrue(line("status:4xx st").suggestions(13).none { it.text == "status:" })
        assertTrue(line("status:4xx -st").suggestions(14).any { it.text == "-status:" }, "an exclusion of the same key is still fine")
        assertTrue(line("GET P").suggestions(5).none { it.text in setOf("POST", "PUT", "PATCH") }, "a second method would be refused")
        assertTrue(line("http w").suggestions(6).none { it.text == "ws" })
        // Duration and size take a lower and an upper bound.
        assertTrue(line("duration:>1s du").suggestions(15).any { it.text == "duration:" })
        // The word being typed is not a word already there.
        assertEquals(listOf("status:4xx"), line("status:4").suggestions(8).map { it.text })
        // Every key listed on an empty line or on Down leaves out the ones already given.
        assertTrue(line("status:4xx ").suggestions(11, explicit = true).none { it.text == "status:" })
    }

    @Test
    fun `nothing is offered that the line would refuse or that undoes a word already there`() {
        val keys = line("-").suggestions(1).map { it.text }
        listOf("-run:", "-since:", "-until:").forEach { assertTrue(it !in keys, it) }
        assertTrue("-has:" in keys, "has: is negated as -has:intervention")
        val seen = Seen(hosts = setOf("api.example.test", "cdn.example.test"))
        assertEquals(listOf("-host:cdn.example.test"), line("host:api.example.test -host:").suggestions(28, seen).map { it.text }, "excluding the host the line asks for leaves nothing")
        assertEquals(listOf("-host:cdn.example.test"), line("-host:").suggestions(6, seen, saved = listOf("host:api.example.test")).map { it.text })
    }

    @Test
    fun `the word at the caret and what follows an inserted suggestion (063)`() {
        assertEquals("sta", QueryLineModel.word("POST sta", 8))
        assertEquals("st", QueryLineModel.word("POST sta", 7))
        assertEquals("", QueryLineModel.word("POST ", 5))
        assertEquals("-host:a", QueryLineModel.word("x -host:a y", 9))
        // A finished word gets a space unless one already follows; a key or a pattern start does not.
        assertEquals(" ", QueryLineModel.tail("status:4xx", ""))
        assertEquals(" ", QueryLineModel.tail("status:4xx", "token"))
        assertEquals("", QueryLineModel.tail("status:4xx", " token"))
        assertEquals("", QueryLineModel.tail("status:", ""))
        assertEquals("", QueryLineModel.tail("body:/", ""))
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
        // Feature 069: hits are those of the rows loaded; more may follow.
        assertEquals("2 / 3 loaded", HitNavigator(entries("a", "b", "c"), more = true).next().next().label)
    }

    @Test
    fun `a live hit keeps saying that more hits follow the loaded ones`() {
        val loaded = HitNavigator(entries("b", "c"), more = true)
        val arrived = loaded.live("a", 0, listOf(hit("a")), newestFirst = true)
        assertEquals("– / 3 loaded", arrived.label)
        val changed = arrived.next().live("a", 0, listOf(hit("a", start = 2)), newestFirst = true)
        assertEquals("1 / 3 loaded", changed.label)
        val left = changed.live("c", 0, null, newestFirst = true)
        assertEquals("1 / 2 loaded", left.label)
        // Everything loaded: a live hit does not invent more.
        assertEquals("– / 3", HitNavigator(entries("b", "c")).live("a", 0, listOf(hit("a")), newestFirst = true).label)
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


    @Test
    fun `an older page's hits join at the oldest end and the current hit stays (070)`() {
        fun e(n: Int) = io.yamsergey.dta.protocol.network.EntryHits("e$n", n.toLong(), listOf(io.yamsergey.dta.protocol.network.SearchHit("e$n", io.yamsergey.dta.protocol.network.SearchPlace.URL, "x", 0, 1, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("x", 0, 1))))
        // Newest at the bottom: the list's order is oldest first.
        val bottom = HitNavigator(listOf(e(3), e(4)), index = 0, more = true)
        val b = bottom.withOlder(listOf(e(2), e(1), e(3)), newestFirst = false, more = false)
        assertEquals(listOf("e1", "e2", "e3", "e4"), b.entries.map { it.id })
        assertEquals("e3", b.current?.id)
        assertEquals("3 / 4", b.label)
        val top = HitNavigator(listOf(e(4), e(3)), index = 1, more = true)
        val t = top.withOlder(listOf(e(2), e(1)), newestFirst = true, more = true)
        assertEquals(listOf("e4", "e3", "e2", "e1"), t.entries.map { it.id })
        assertEquals("2 / 4 loaded", t.label)
    }

    @Test
    fun `a row's hits are found without walking every hit, as the list paints each cell (070)`() {
        val hit = io.yamsergey.dta.protocol.network.SearchHit("x", io.yamsergey.dta.protocol.network.SearchPlace.URL, "x", 0, 1, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("x", 0, 1))
        val nav = HitNavigator((0 until 50_000).map { io.yamsergey.dta.protocol.network.EntryHits("e$it", it.toLong(), listOf(hit)) })
        val t = System.nanoTime()
        // A second of painting: 10 frames of 200 cells, 50 times over.
        repeat(100_000) { nav.hitsOf("e${49_999 - it % 50_000}") }
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(listOf(hit), nav.hitsOf("e7"))
        assertEquals(null, nav.hitsOf("none"))
        kotlin.test.assertTrue(ms < 500, "100 000 lookups over 50 000 hits took $ms ms")
    }

    @Test
    fun `typos is offered as a key with on and off (decision 2026-11-22)`() {
        assertTrue(QueryLineModel("ty").suggestions(2).any { it.text == "typos:" && it.hint!!.contains("off by default") })
        assertEquals(listOf("typos:on", "typos:off"), QueryLineModel("typos:").suggestions(6).map { it.text })
        assertEquals(emptyList(), QueryLineModel("typos:on ty").suggestions(11).filter { it.text == "typos:" }, "given once")
    }

    @Test
    fun `the words are marked exactly unless typos are on`() {
        val text = "the token expired"
        assertEquals(emptyList(), QueryLineModel("tokan").marks.spans(text))
        assertEquals(listOf("token"), QueryLineModel("typos:on tokan").marks.spans(text).map { text.substring(it) })
        assertEquals(listOf("token"), QueryLineModel("TOKEN").marks.spans(text).map { text.substring(it) })
    }

    @Test
    fun `body filters are marked in the bodies they look in, and the line finds with them alone (D-034)`() {
        val line = QueryLineModel("body:main req:screen resp:/\\d{3}/ -body:skip")
        assertTrue(line.finds, "a body filter alone points at hits")
        assertEquals(false, line.hasSearch, "no search words")
        assertEquals(false, QueryLineModel("host:a.test").finds)
        assertEquals(false, QueryLineModel("body:main status:9").finds, "a line with problems is not applied")
        val m = line.marks
        val text = """{"screen":"domain","code":200,"skip":1}"""
        fun shown(x: SearchMarks) = x.spans(text).map { text.substring(it) }
        assertEquals(emptyList(), shown(m), "headers and URLs: no body filter")
        assertEquals(listOf("screen", "main"), shown(m.inBody(SearchMarks.BodySide.REQUEST)))
        assertEquals(listOf("main", "200"), shown(m.inBody(SearchMarks.BodySide.RESPONSE)))
        assertEquals(listOf("main"), shown(m.inBody(SearchMarks.BodySide.ANY)), "a message: body: only")
    }

    @Test
    fun `body text is found again in a pretty-printed body`() {
        val pretty = "{\n  \"screen\": \"Main\",\n  \"items\": [\n    1,\n    2\n  ]\n}"
        assertEquals(listOf("\"screen\": \"Main\""), SearchMarks.textSpans(pretty, "\"screen\":\"main\"").map { pretty.substring(it) })
        assertEquals(listOf("[\n    1,\n    2"), SearchMarks.textSpans(pretty, "[1,2").map { pretty.substring(it) })
        assertEquals(listOf("Main"), SearchMarks.textSpans(pretty, "main").map { pretty.substring(it) })
        assertEquals(emptyList(), SearchMarks.textSpans(pretty, "rain"))
    }

    @Test
    fun `a pattern is marked everywhere it matches, and a runaway one stops`() {
        assertEquals(listOf(0..2, 8..10), SearchMarks.patternSpans("200 ok, 404", "\\d+"))
        assertEquals(emptyList(), SearchMarks.patternSpans("x", "("), "does not compile")
        val t0 = System.nanoTime()
        SearchMarks.patternSpans("x".repeat(5_000), "(x+x+)+y")
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "took ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
