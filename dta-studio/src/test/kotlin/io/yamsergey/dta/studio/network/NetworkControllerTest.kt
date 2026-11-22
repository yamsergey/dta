package io.yamsergey.dta.studio.network

import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkControllerTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)
    private val network = NetworkController(context, scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun await(check: (NetworkViewState) -> Boolean): NetworkViewState =
        runCatching { withTimeout(5_000) { network.state.first(check) } }.getOrElse {
            error("timed out; network=${network.state.value.let { s -> s.copy(list = io.yamsergey.dta.present.network.LiveList()) to s.list.rows.size }} context=${context.state.value}")
        }

    private fun http(seq: Int, state: HttpState = HttpState.COMPLETE) =
        HttpEntry("r1-1:$seq", "r1-1", 1_700_000_000_000 + seq * 1000L, "okhttp3", method = "GET", url = "https://api.example.com/$seq", state = state, status = if (state == HttpState.COMPLETE) 200 else null)

    @Test
    fun `loads the newest page, then follows live entries and updates in place`() = runBlocking<Unit> {
        session.start()
        val loaded = await { it.list.rows.size == 3 && it.watching }
        assertEquals(500, daemon.ops.queries.last().second.limit)
        daemon.ops.events.emit(NetworkEntryEvent(http(10, HttpState.PENDING), EntryChange.CREATED))
        await { it.list.rows.last().id == "r1-1:10" }
        daemon.ops.events.emit(NetworkEntryEvent(http(10), EntryChange.UPDATED))
        val updated = await { it.list.rows.last().entry["state"] == JsonPrimitive("complete") }
        assertEquals(4, updated.list.rows.size)
    }

    /**
     * 070 (EAP .133 on another machine): a list the daemon abandoned for a newer query of the tab
     * said «SupersededException: superseded by a newer query of view …» and ended the live list.
     * Its answer is not needed: nothing is said.
     */
    @Test
    fun `a list abandoned for a newer query of the tab is not an error`() = runBlocking<Unit> {
        val entries = daemon.ops.listed
        var first = true
        daemon.ops.listed = { q -> if (first) { first = false; throw io.yamsergey.dta.daemon.network.HostIndex.SupersededException(NetworkController.VIEW) } else entries(q) }
        session.start()
        until { !first }
        kotlinx.coroutines.delay(300)
        assertEquals(null, network.state.value.error, "nothing said for an answer nobody needs")
    }

    @Test
    fun `reveal leaves an archive scope and selects the entry (from the Timeline tab)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setArchive("all")
        await { it.archive == "all" }
        network.reveal("r1-1:2")
        val s = await { it.archive == null && it.list.selectedId == "r1-1:2" }
        assertEquals("r1-1:2", s.list.selectedId)
    }

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) kotlinx.coroutines.delay(20) }

    @Test
    fun `the query line is the list's query, search words list only the matches (040, 041, 043)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("status:4xx")
        until { daemon.ops.queries.any { it.second.q == "status:4xx" } }
        assertTrue(daemon.ops.searches.isEmpty(), "no search words, no search")
        daemon.ops.searchHits = listOf(io.yamsergey.dta.protocol.network.EntryHits("r1-1:2", 0, listOf(
            io.yamsergey.dta.protocol.network.SearchHit("r1-1:2", io.yamsergey.dta.protocol.network.SearchPlace.URL, "api", 8, 11, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("api", 0, 3)),
        )))
        network.setQuery("status:4xx api")
        until { daemon.ops.searches.any { it.q == "status:4xx api" } }
        val hit = await { it.hits.total == 1 }
        assertEquals("– / 1", hit.hits.label)
        // Feature 041: only the matches are listed — the words are the list's query.
        until { daemon.ops.queries.last().second.q == "status:4xx api" }
        assertEquals(false, daemon.ops.queries.last().second.allRows)
        network.setQuery("status:4xx api")
        await { it.hits.total == 1 }
        network.nextHit()
        val selected = await { it.list.selectedId == "r1-1:2" }
        assertEquals("1 / 1", selected.hits.label)
        network.setQuery("")
        until { daemon.ops.queries.last().second.q == null }
        await { it.hits.total == 0 }
    }

    @Test
    fun `a query that leaves the selection out clears it quietly, live matches keep it (042)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        daemon.ops.listed = { q -> if (q.q == "cart") daemon.ops.entries.filter { it.id == "r1-1:2" } else daemon.ops.entries }
        network.list(io.yamsergey.dta.present.network.LiveEvent.Selected("r1-1:1"))
        network.setQuery("cart")
        val quiet = await { it.list.rows.size == 1 }
        assertEquals(null to false, quiet.list.selectedId to quiet.list.selectedGone)
        network.list(io.yamsergey.dta.present.network.LiveEvent.Selected("r1-1:2"))
        val lists = daemon.ops.queries.size
        daemon.ops.events.emit(NetworkEntryEvent(http(10), EntryChange.CREATED))
        until { daemon.ops.queries.size > lists }
        kotlinx.coroutines.delay(200)
        val kept = network.state.value
        assertEquals("r1-1:2" to false, kept.list.selectedId to kept.list.selectedGone)
    }

    @Test
    fun `typed search words, matches arriving faster than the settle time still show while traffic goes on`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        val arrived = java.util.concurrent.CopyOnWriteArrayList<HttpEntry>()
        daemon.ops.listed = { q -> if (q.q == "api") daemon.ops.entries + arrived else daemon.ops.entries }
        network.setQuery("api")
        until { daemon.ops.queries.any { it.second.q == "api" } }
        await { it.list.rows.size == 3 }
        // A socket sending every 300 ms: never a whole second without traffic.
        val traffic = launch(Dispatchers.IO) {
            var seq = 100
            while (true) {
                val e = http(seq++)
                arrived += e
                daemon.ops.events.emit(NetworkEntryEvent(e, EntryChange.CREATED))
                kotlinx.coroutines.delay(300)
            }
        }
        try {
            val s = runCatching { withTimeout(4_000) { network.state.first { it.list.rows.size > 3 } } }.getOrNull()
            assertTrue(s != null, "no new match listed while traffic kept arriving: ${network.state.value.list.rows.size} rows")
        } finally {
            traffic.cancel()
        }
    }

    private fun hit(id: String) = io.yamsergey.dta.protocol.network.SearchHit(id, io.yamsergey.dta.protocol.network.SearchPlace.URL, "api", 8, 11, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("api", 0, 3))

    @Test
    fun `with live decisions, matches join and hits grow while traffic goes on, and nothing is listed again (067)`() = runBlocking<Unit> {
        daemon.ops.decide = { q, e -> io.yamsergey.dta.protocol.network.EntryDecision(true, hits = if (q.q?.contains("api") == true) listOf(hit(e.id)) else null) to false }
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        until { daemon.ops.queries.any { it.second.q == "api" } }
        await { it.list.rows.size == 3 && it.watching }
        until { daemon.ops.searches.any { it.q == "api" } }
        await { !it.searching }
        val lists = daemon.ops.queries.size
        repeat(10) { daemon.ops.events.emit(NetworkEntryEvent(http(100 + it), EntryChange.CREATED)) }
        val s = await { it.list.rows.size == 13 }
        assertEquals(10, s.hits.total, "each decision's hits are counted")
        kotlinx.coroutines.delay(1_500)
        assertEquals(lists, daemon.ops.queries.size, "no list asked again for live traffic")
        assertTrue(daemon.ops.views.all { it?.endsWith(":network-tab") == true }, "the tab lists as its live view: ${daemon.ops.views}")
    }

    @Test
    fun `new search words say Searching at once, never No matches before the search starts`() = runBlocking<Unit> {
        pagedHistory(listOf(3, 2, 1), firstPage = 3)
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        network.state.value.let { assertTrue(it.searching && it.hits.total == 0, "searching from the moment the words change: ${it.searching} ${it.hits.label}") }
        await { it.hits.total == 3 && !it.searching }
        // The new words' list is slow: all the while it loads (the old subscription cancelled), the line says Searching.
        val slow = daemon.ops.listed
        daemon.ops.listed = { q -> if (q.q?.contains("other") == true) Thread.sleep(800); slow(q) }
        network.setQuery("api other")
        network.state.value.let { assertTrue(it.searching && it.hits.total == 0, "the old words' hits are not shown for the new ones: ${it.hits.label}") }
        kotlinx.coroutines.delay(500)
        network.state.value.let { assertTrue(it.searching, "still searching while the new list loads, not No matches") }
        await { !it.searching }
        network.setQuery("")
        assertTrue(!network.state.value.searching, "no words, no search")
    }

    @Test
    fun `matches decided while a search runs keep their hits when it answers (070)`() = runBlocking<Unit> {
        daemon.ops.decide = { q, e -> io.yamsergey.dta.protocol.network.EntryDecision(true, hits = if (q.q?.contains("api") == true) listOf(hit(e.id)) else null) to false }
        pagedHistory(listOf(3, 2, 1), firstPage = 3)
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        await { it.hits.total == 3 && !it.searching && it.watching }
        // The next search is slow; traffic decided meanwhile has hits the search never saw.
        val fast = daemon.ops.searchPage!!
        daemon.ops.searchPage = { q -> Thread.sleep(800); fast(q) }
        network.search()
        await { it.searching }
        repeat(4) { daemon.ops.events.emit(NetworkEntryEvent(http(100 + it), EntryChange.CREATED)) }
        await { it.list.rows.size == 7 }
        val s = await { !it.searching }
        assertEquals(7, s.hits.total, "every loaded match has its hits: ${s.hits.entries.map { it.id }}")
        assertEquals("– / 7", s.hits.label)
    }

    @Test
    fun `a search reaches the oldest loaded row though newer matches arrived since the list loaded (070)`() = runBlocking<Unit> {
        val listed = listOf(3, 2, 1)
        // The history the search reads already holds 5 and 4, newer than the rows listed.
        val history = listOf(5, 4, 3, 2, 1)
        daemon.ops.listed = { _ -> listed.map { http(it) } }
        daemon.ops.searchPage = { q ->
            val from = q.before?.removePrefix("s")?.toInt() ?: 0
            val slice = history.drop(from).take(q.limit)
            io.yamsergey.dta.protocol.network.SearchPage(
                slice.map { io.yamsergey.dta.protocol.network.EntryHits("r1-1:$it", 1_700_000_000_000 + it * 1000L, listOf(hit("r1-1:$it"))) },
                next = if (from + slice.size < history.size) "s${from + slice.size}" else null,
            )
        }
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        val s = await { !it.searching && it.hits.total > 0 }
        assertEquals(listOf("r1-1:1", "r1-1:2", "r1-1:3"), s.hits.entries.map { it.id }.sorted(), "every loaded row has its hits")
    }

    @Test
    fun `an older page brings its rows' hits, and nothing is searched again (070)`() = runBlocking<Unit> {
        pagedHistory(listOf(3, 2, 1, -1, -2), firstPage = 3)
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        await { it.hits.total == 3 && !it.searching }
        network.nextHit()
        val current = await { it.hits.index >= 0 }.hits.current!!.id
        val searches = daemon.ops.searches.size
        network.loadOlder()
        val s = await { it.list.rows.size == 5 && it.hits.total == 5 }
        assertEquals("1 / 5", s.hits.label, "the hit stepped onto stays current: ${s.hits.label}")
        assertEquals(current, s.hits.current?.id)
        val newestFirst = listOf("r1-1:3", "r1-1:2", "r1-1:1", "r1-1:-1", "r1-1:-2")
        assertEquals(if (s.hitsNewestFirst) newestFirst else newestFirst.asReversed(), s.hits.entries.map { it.id }, "in the list's order")
        kotlinx.coroutines.delay(300)
        assertEquals(searches, daemon.ops.searches.size, "the page's own hits, no search of every loaded row")
    }

    /** D-022: Load older (the row above the list, or scrolling to its top) joins the next page under the loaded rows, once. */
    @Test
    fun `load older joins the next page without gap, one page at a time, live (D-022)`() = runBlocking<Unit> {
        pagedHistory(listOf(3, 2, 1, -1, -2), firstPage = 3)
        session.start()
        val first = await { it.list.rows.size == 3 && it.watching }
        assertEquals("l3", first.list.older)
        // The row and the scroll to the top may both ask: one page is loaded, not two.
        network.loadOlder(); network.loadOlder()
        val s = await { it.list.rows.size == 5 && !it.loadingOlder }
        assertEquals(listOf(-2, -1, 1, 2, 3).map { "r1-1:$it" }, s.list.rows.map { it.id }, "oldest to newest, no gap, no duplicate")
        assertEquals(null, s.list.older, "everything is loaded")
        assertEquals(1, daemon.ops.queries.count { it.second.before == "l3" }, "one page asked: ${daemon.ops.queries.map { it.second.before }}")
        assertEquals(null, s.error)
        assertTrue(s.watching, "the list stays live")
        network.loadOlder()
        kotlinx.coroutines.delay(200)
        assertEquals(1, daemon.ops.queries.count { it.second.before != null }, "nothing older to load")
    }

    /** A history of [ids] newest first that match "api"; the list pages after [firstPage] rows, the search by its limit. */
    private fun pagedHistory(ids: List<Int>, firstPage: Int) {
        val byId = ids.associateWith { http(it) }
        daemon.ops.listed = { q ->
            val from = q.before?.removePrefix("l")?.toInt() ?: 0
            ids.drop(from).take(if (q.before == null) firstPage else q.limit).map { byId.getValue(it) }
        }
        daemon.ops.listNext = { q -> if (q.before == null && ids.size > firstPage) "l$firstPage" else null }
        daemon.ops.listHits = { q, page -> if (q.q?.contains("api") == true) page.associate { it.id to listOf(hit(it.id)) } else null }
        daemon.ops.searchPage = { q ->
            val from = q.before?.removePrefix("s")?.toInt() ?: 0
            val slice = ids.drop(from).take(q.limit)
            io.yamsergey.dta.protocol.network.SearchPage(
                slice.map { io.yamsergey.dta.protocol.network.EntryHits("r1-1:$it", 0, listOf(hit("r1-1:$it"))) },
                next = if (from + slice.size < ids.size) "s${from + slice.size}" else null,
            )
        }
    }

    @Test
    fun `hits are those of the rows loaded, and Next past the last loads the next page (070)`() = runBlocking<Unit> {
        pagedHistory(listOf(3, 2, 1, -1, -2), firstPage = 3)
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        val first = await { it.hits.total == 3 && !it.searching }
        assertEquals("– / 3 loaded", first.hits.label)
        daemon.ops.searches.last().let {
            assertEquals(3, it.limit, "as many as the list has loaded")
            assertEquals(io.yamsergey.dta.protocol.network.Order.NEWEST_FIRST, it.order, "the newest, as the list loads them")
        }
        repeat(3) { network.nextHit() }
        assertEquals("3 / 3 loaded", await { it.hits.index == 2 }.hits.label)
        network.nextHit()
        val more = await { it.hits.total == 5 && it.hits.current?.id == "r1-1:-1" }
        assertEquals("4 / 5", more.hits.label, "the next page loaded: everything matching is in")
        assertTrue(daemon.ops.queries.any { it.second.before == "l3" }, "the list's next page was asked")
        assertEquals("r1-1:-1", await { it.list.selectedId == "r1-1:-1" }.list.selectedId)
        network.nextHit(); network.nextHit()
        assertEquals("1 / 5", await { it.hits.index == 0 }.hits.label, "all loaded: Next wraps")
    }

    @Test
    fun `newest at the bottom, Previous past the first hit loads the older page above (070)`() = runBlocking<Unit> {
        pagedHistory(listOf(3, 2, 1, -1, -2), firstPage = 3)
        network.setHitsNewestFirst(false)
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("api")
        await { it.hits.total == 3 && !it.searching }
        assertEquals(io.yamsergey.dta.protocol.network.Order.NEWEST_FIRST, daemon.ops.searches.last().order)
        network.nextHit()
        assertEquals("r1-1:1", await { it.hits.index == 0 }.hits.current?.id, "top to bottom: the oldest loaded first")
        network.previousHit()
        val more = await { it.hits.total == 5 && it.hits.current?.id == "r1-1:-1" }
        assertEquals("2 / 5", more.hits.label)
    }

    @Test
    fun `more rows loaded than one search page holds are searched page by page (070)`() = runBlocking<Unit> {
        pagedHistory((1200 downTo 1).toList(), firstPage = 1200)
        session.start()
        await { it.list.rows.size == 1200 && it.watching }
        network.setQuery("api")
        val s = await { it.hits.total == 1200 && !it.searching }
        assertEquals("– / 1200", s.hits.label)
        val pages = daemon.ops.searches.takeLast(2)
        assertEquals(listOf(1000, 200), pages.map { it.limit })
        assertEquals(listOf(null, "s1000"), pages.map { it.before })
    }

    @Test
    fun `an entry that stops matching leaves the list (067)`() = runBlocking<Unit> {
        daemon.ops.decide = { _, e -> if (e.id == "r1-1:2") io.yamsergey.dta.protocol.network.EntryDecision(false) to true else io.yamsergey.dta.protocol.network.EntryDecision(true) to false }
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        daemon.ops.events.emit(NetworkEntryEvent(http(2), EntryChange.UPDATED))
        val s = await { it.list.rows.size == 2 }
        assertTrue(s.list.rows.none { it.id == "r1-1:2" })
    }

    @Test
    fun `a line with problems changes nothing but says what is wrong (040)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        val lists = daemon.ops.queries.size
        network.setQuery("stats:4xx")
        val s = await { it.line.text == "stats:4xx" }
        assertTrue(s.line.parsed.problems.single().message.contains("did you mean status:"))
        kotlinx.coroutines.delay(200)
        assertEquals(lists, daemon.ops.queries.size)
    }

    /**
     * D-021: an invalid line keeps the list of the last valid query, live and without an error; the
     * kind switch applies to that query — the invalid one never reaches the daemon.
     */
    @Test
    fun `an invalid line keeps the last valid query live, and a kind switch applies to it (D-021)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("url:example")
        until { daemon.ops.queries.any { it.second.q == "url:example" } }
        await { it.watching && it.applied.text == "url:example" }
        network.setQuery("url:example status:abc")
        val bad = await { it.line.text == "url:example status:abc" }
        assertEquals("url:example", bad.applied.text, "the list keeps the last valid query")
        assertTrue(bad.line.parsed.problems.isNotEmpty())
        network.setKind(io.yamsergey.dta.present.network.KindChoice.HTTP)
        until { daemon.ops.queries.any { it.second.q == "http url:example" } }
        val s = await { it.watching && it.kind == io.yamsergey.dta.present.network.KindChoice.HTTP && !it.loading }
        assertEquals(null, s.error)
        assertEquals(3, s.list.rows.size, "the last valid query's rows")
        assertTrue(daemon.ops.queries.none { it.second.q?.contains("abc") == true }, "the invalid line never reached the daemon: ${daemon.ops.queries.map { it.second.q }}")
        assertTrue(daemon.ops.searches.none { it.q?.contains("abc") == true })
        assertEquals("url:example status:abc", s.line.text, "the field keeps what was typed")
    }

    @Test
    fun `the kind switch queries WebSocket traffic only, then everything again, and stays out of the line (043)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 }
        network.setKind(io.yamsergey.dta.present.network.KindChoice.WEBSOCKET)
        until { daemon.ops.queries.any { it.second.q == "ws" } }
        assertEquals("", network.state.value.line.text)
        network.setKind(io.yamsergey.dta.present.network.KindChoice.ALL)
        until { daemon.ops.queries.last().second.q == null }
    }

    @Test
    fun `Enter saves the line's filters, the search word stays, the list asks with what is left (043)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("-host:t.example.test status:2xx ACME")
        assertTrue(network.commitLine("-host:t.example.test status:2xx ACME"))
        val saved = await { it.saved.saved == listOf("-host:t.example.test", "status:2xx") }
        assertEquals("ACME", saved.line.text)
        assertEquals(listOf("-host:t.example.test", "status:2xx"), daemon.ops.savedWords.toList())
        until { daemon.ops.queries.last().second.q == "ACME" }
        assertEquals(false, network.commitLine("ACME"), "only search words: nothing to save")
    }

    @Test
    fun `removing a chip, editing one, and another client's change (043)`() = runBlocking<Unit> {
        daemon.ops.savedWords += listOf("-url:/a", "status:2xx")
        session.start()
        await { it.saved.saved.size == 2 }
        network.removeSaved("-url:/a")
        await { it.saved.saved == listOf("status:2xx") }
        network.editSaved("status:2xx")
        val edited = await { it.saved.saved.isEmpty() }
        assertEquals("status:2xx", edited.line.text)
        daemon.ops.savedWords += "-url:/b"
        withTimeout(8_000) { network.state.first { it.saved.saved == listOf("-url:/b") } }
    }

    @Test
    fun `after the daemon is replaced the saved filters still follow it`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        val other = daemon.connect(ClientKind.CLI).client
        other.request(io.yamsergey.dta.protocol.ops.DaemonMethods.REPLACE, io.yamsergey.dta.client.DaemonClient.replaceParams(io.yamsergey.dta.protocol.ops.VersionIdentity("0.1.0", "2026-12-31T00:00:00Z", 1)))
        until { (session.state.value as? DaemonSession.State.Connected)?.statements?.any { "replaced" in it } == true }
        await { it.list.rows.size == 3 && it.watching }
        daemon.ops.savedWords += "-url:/after"
        withTimeout(8_000) { network.state.first { it.saved.saved == listOf("-url:/after") } }
    }

    @Test
    fun `a clear by another client empties the list and says who cleared it`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        daemon.connect(ClientKind.CLI).client.call("network.clear", kotlinx.serialization.json.JsonObject(mapOf(
            "device" to JsonPrimitive("emulator-5554"), "app" to JsonPrimitive("com.example.shop"),
        )))
        val cleared = await { it.list.rows.isEmpty() }
        assertTrue(cleared.statements.any { it.detail == "History cleared by cli: 3 entries removed." }, "${cleared.statements}")
    }

    @Test
    fun `clear empties the app's history and the list`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 }
        network.clear()
        withTimeout(5_000) { while (daemon.ops.cleared == 0) kotlinx.coroutines.delay(20) }
        assertTrue(daemon.ops.cleared == 1)
    }

    @Test
    fun `switching the app drops the old list and its watch, an app without DTA loads nothing`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        context.chooseApp("com.example.other")
        val other = await { it.app == "com.example.other" && !it.watching }
        assertEquals(0, other.list.rows.size)
        withTimeout(5_000) { context.state.first { it.connection == io.yamsergey.dta.present.context.Connection.NO_DTA } }
        val before = daemon.ops.queries.size
        context.chooseApp("com.example.shop")
        await { it.app == "com.example.shop" && it.list.rows.size == 3 && it.watching }
        assertTrue(daemon.ops.queries.size > before)
    }

    private fun waiting(catchingUp: Long?, reading: Boolean = false) {
        daemon.ops.history = daemon.ops.history.copy(device = daemon.ops.history.device!!.copy(catchingUp = catchingUp, reading = reading))
    }

    @Test
    fun `a wait that lasts is one background task with the app and how far it is, and it ends when caught up (068)`() = runBlocking<Unit> {
        network.waitPollMs = 20
        network.waitShowAfterMs = 150
        waiting(48_000)
        session.start()
        await { it.list.rows.size == 3 }
        val shown = await { it.wait != null }
        assertTrue(shown.wait!!.text.startsWith("DTA: catching up "), shown.wait!!.text)
        assertEquals(0.0, shown.wait!!.fraction!!, 0.001)
        waiting(12_000)
        await { s -> s.wait?.fraction?.let { kotlin.math.abs(it - 0.75) < 0.001 } == true }
        waiting(null, reading = true)
        val reading = await { it.wait?.text?.startsWith("DTA: reading the history of ") == true }
        assertEquals(null, reading.wait!!.fraction, "how far the app is in its history is not known")
        waiting(null)
        await { it.wait == null }
    }

    @Test
    fun `a short wait shows nothing (068)`() = runBlocking<Unit> {
        network.waitPollMs = 20
        network.waitShowAfterMs = 400
        waiting(300)
        session.start()
        await { it.list.rows.size == 3 }
        kotlinx.coroutines.delay(150)
        waiting(null)
        kotlinx.coroutines.delay(600)
        assertEquals(null, network.state.value.wait)
    }
}
