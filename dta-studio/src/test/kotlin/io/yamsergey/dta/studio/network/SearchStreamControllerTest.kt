package io.yamsergey.dta.studio.network

import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.EntryDecision
import io.yamsergey.dta.protocol.network.FindEnd
import io.yamsergey.dta.protocol.network.FindState
import io.yamsergey.dta.protocol.network.FoundItem
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import io.yamsergey.dta.protocol.network.SearchStoppedException
import io.yamsergey.dta.protocol.network.find
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 071 (US3, US5; FR-006/007/008/015): the tab's streamed search, its Stop, the switch and a restart. */
class SearchStreamControllerTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)
    private val network = NetworkController(context, scope)

    @AfterTest
    fun tearDown() { scope.cancel(); daemon.close() }

    /** Waits for [check]; after it the tab's error is null and its live list watches (feedback: robot checks). */
    private suspend fun await(check: (NetworkViewState) -> Boolean): NetworkViewState {
        val s = runCatching { withTimeout(5_000) { network.state.first(check) } }.getOrElse {
            error("timed out; state=${network.state.value.let { s -> Triple(s.find, s.list.rows.map { it.id }, s.error) }}")
        }
        assertNull(s.error, "error after the step")
        assertTrue(s.watching, "the live list watches after the step")
        return s
    }

    private fun http(n: Int, run: String = "r1-1") = HttpEntry("$run:$n", run, 1_700_000_000_000 + n * 1000L, "okhttp3", method = "GET", url = "https://api.example.com/token/$n", state = HttpState.COMPLETE, status = 200)
    private fun hit(id: String) = SearchHit(id, SearchPlace.URL, "token", 24, 29, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("…/token/", 1, 6))

    /** A walk that gives [first] rows at once, then waits in a runaway match until stopped; [ended] when it stopped. */
    private fun slowWalk(first: Int, ended: CountDownLatch) = { _: io.yamsergey.dta.protocol.network.NetworkQuery, _: Int? ->
        { stop: io.yamsergey.dta.protocol.network.PatternStop, onItem: (FoundItem) -> Unit ->
            (first downTo 1).forEach { onItem(FoundItem(http(it), listOf(hit("r1-1:$it")))) }
            try {
                io.yamsergey.dta.protocol.network.BodyPattern.find(java.util.regex.Pattern.compile("(x+x+)+y"), "x".repeat(1 shl 20)) { if (stop.stopped()) throw SearchStoppedException() }
            } finally { ended.countDown() }
            FindEnd(null, null)
        }
    }

    @Test
    fun `a search streams its rows and hits while searching, then says done`() = runBlocking<Unit> {
        daemon.ops.findWalk = { _, _ -> { _, onItem -> (3 downTo 1).forEach { onItem(FoundItem(http(it), listOf(hit("r1-1:$it")))) }; FindEnd(null, null) } }
        session.start()
        await { it.watching }
        network.setQuery("token")
        val done = await { it.find == FindState.DONE }
        assertEquals(listOf("r1-1:1", "r1-1:2", "r1-1:3"), done.list.rows.map { it.id })
        assertEquals(3, done.hits.total)
        assertEquals(false, done.hits.more)
        assertEquals(false, done.searching)
        assertEquals("r1-1", done.scope!!.run!!.runId)
        assertEquals(false, daemon.ops.finds.last().archive)
    }

    @Test
    fun `Stop ends a runaway search on the daemon and says stopped, never done, and the live list goes on`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        daemon.ops.findWalk = slowWalk(2, ended)
        session.start()
        await { it.watching }
        network.setQuery("token")
        val searching = await { it.find == FindState.SEARCHING && it.list.rows.size == 2 }
        assertTrue(searching.hits.more, "more may come while it searches")
        network.stopSearch()
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the daemon's walk stopped")
        val stopped = await { it.find == FindState.STOPPED }
        delay(300)
        assertEquals(FindState.STOPPED, network.state.value.find, "a stopped search is never said done")
        assertEquals(2, stopped.list.rows.size)
        // What must not stop: live entries still join (067 decisions), and the tab still watches.
        daemon.ops.decide = { _, e -> EntryDecision(true, hits = listOf(hit(e.id))) to false }
        daemon.ops.events.emit(NetworkEntryEvent(http(9), EntryChange.CREATED))
        await { s -> s.list.rows.any { it.id == "r1-1:9" } }
    }

    @Test
    fun `a new query supersedes the running search, and its later rows are never shown`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        daemon.ops.findWalk = slowWalk(2, ended)
        session.start()
        await { it.watching }
        network.setQuery("token")
        await { it.find == FindState.SEARCHING && it.list.rows.size == 2 }
        daemon.ops.findWalk = { q, _ -> { _, onItem -> if (q.q == "other") onItem(FoundItem(http(7), listOf(hit("r1-1:7")))); FindEnd(null, null) } }
        network.setQuery("other")
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the older search was stopped")
        val done = await { it.find == FindState.DONE }
        assertEquals(listOf("r1-1:7"), done.list.rows.map { it.id })
    }

    @Test
    fun `the Archive switch scopes the search and starts it over`() = runBlocking<Unit> {
        daemon.ops.findWalk = { _, _ -> { _, onItem -> onItem(FoundItem(http(1), listOf(hit("r1-1:1")))); FindEnd(null, null) } }
        session.start()
        await { it.watching }
        network.setQuery("token")
        await { it.find == FindState.DONE }
        network.setArchiveOn(true)
        val on = await { it.find == FindState.DONE && it.scope?.archive == true }
        assertTrue(daemon.ops.finds.last().archive)
        assertEquals(1, on.list.rows.size)
        // And the plain list takes it too.
        network.setQuery("")
        await { it.find == null && it.list.rows.size == 3 }
        assertTrue(daemon.ops.queries.last().second.archive)
    }

    @Test
    fun `Next past the last hit found waits for the next one while it searches`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        daemon.ops.findWalk = { _, _ ->
            { _, onItem ->
                onItem(FoundItem(http(5), listOf(hit("r1-1:5"))))
                release.await(5, TimeUnit.SECONDS)
                onItem(FoundItem(http(4), listOf(hit("r1-1:4"))))
                FindEnd(null, null)
            }
        }
        session.start()
        await { it.watching }
        network.setQuery("token")
        await { it.hits.total == 1 }
        network.nextHit()
        await { it.hits.current?.id == "r1-1:5" }
        network.nextHit()
        delay(200)
        assertEquals("r1-1:5", network.state.value.hits.current?.id, "it waits, no wrap while searching")
        release.countDown()
        await { it.hits.current?.id == "r1-1:4" }
    }

    @Test
    fun `a newer run while the tab shows the current one lists the new run and names the previous one`() = runBlocking<Unit> {
        daemon.ops.findScope = daemon.ops.findScope.copy(run = io.yamsergey.dta.protocol.network.ScopeRun("r1-1", 3, startedMs = 1_700_000_000_000))
        daemon.ops.decide = { _, e -> EntryDecision(true) to false }
        session.start()
        val first = await { it.watching && it.list.rows.size == 3 }
        assertEquals(null, first.restart)
        daemon.ops.listScope = io.yamsergey.dta.protocol.network.HistoryScope(false, 1, io.yamsergey.dta.protocol.network.ScopeRun("r2-1", 1, startedMs = 1_700_000_100_000),
            previous = io.yamsergey.dta.protocol.network.ScopeRun("r1-1", 3, startedMs = 1_700_000_000_000, endedMs = 1_700_000_090_000, ended = io.yamsergey.dta.protocol.network.RunEnd.PROCESS_ENDED))
        daemon.ops.events.emit(NetworkEntryEvent(http(1, run = "r2-1"), EntryChange.CREATED))
        val moved = await { it.restart != null }
        assertTrue(moved.restart!!.startsWith("The app restarted at "), moved.restart)
        assertTrue("Previous run: 3 entries" in moved.restart!!, moved.restart)
    }

    @Test
    fun `the app starting again during a search stops it, and the same search starts over in the new run`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        daemon.ops.findWalk = slowWalk(2, ended)
        daemon.ops.decide = { _, _ -> EntryDecision(true) to false }
        session.start()
        await { it.watching }
        network.setQuery("token")
        await { it.find == FindState.SEARCHING && it.list.rows.size == 2 }
        // The new run's search: done at once.
        daemon.ops.findScope = daemon.ops.findScope.copy(run = io.yamsergey.dta.protocol.network.ScopeRun("r2-1", 1, startedMs = 1_700_000_100_000))
        daemon.ops.listScope = daemon.ops.listScope!!.copy(run = io.yamsergey.dta.protocol.network.ScopeRun("r2-1", 1, startedMs = 1_700_000_100_000))
        daemon.ops.findWalk = { _, _ -> { _, onItem -> onItem(FoundItem(http(1, run = "r2-1"), listOf(hit("r2-1:1")))); FindEnd(null, null) } }
        daemon.ops.events.emit(NetworkEntryEvent(http(1, run = "r2-1"), EntryChange.CREATED))
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the search over the run that ended was stopped")
        val moved = await { it.find == FindState.DONE && it.scope?.run?.runId == "r2-1" }
        assertEquals(listOf("r2-1:1"), moved.list.rows.map { it.id }, "nothing of the earlier run's search is mixed in")
        assertEquals(2, daemon.ops.finds.size)
        assertTrue(moved.restart != null, "the banner names the run before")
    }

    @Test
    fun `an earlier run's late update during a search does not stop it`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        daemon.ops.findWalk = slowWalk(2, ended)
        daemon.ops.decide = { _, _ -> EntryDecision(true) to false }
        session.start()
        await { it.watching }
        network.setQuery("token")
        await { it.find == FindState.SEARCHING && it.list.rows.size == 2 }
        daemon.ops.events.emit(NetworkEntryEvent(http(7, run = "r0-1"), EntryChange.UPDATED))
        delay(500)
        assertEquals(1L, ended.count, "the search goes on")
        assertEquals(FindState.SEARCHING, network.state.value.find)
        assertEquals(1, daemon.ops.finds.size)
        network.stopSearch()
        assertTrue(ended.await(2, TimeUnit.SECONDS))
    }

    /** D-034: a body filter's hit, in the request body (the Payload tab). */
    private fun bodyHit(id: String) = SearchHit(id, SearchPlace.REQUEST_BODY, "main", 11, 15, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("{\"screen\":\"domain\"}", 13, 17), io.yamsergey.dta.protocol.network.BodyPart.REQUEST)

    @Test
    fun `body-main alone - its rows carry where it matched, kept through a re-typed line and live decisions (D-034)`() = runBlocking<Unit> {
        daemon.ops.findWalk = { _, _ -> { _, onItem -> (2 downTo 1).forEach { onItem(FoundItem(http(it), listOf(bodyHit("r1-1:$it")))) }; FindEnd(null, null) } }
        session.start()
        await { it.watching }
        network.setQuery("body:main")
        val done = await { it.find == FindState.DONE }
        assertTrue(done.applied.finds, "a body filter alone finds")
        assertEquals(2, done.hits.total, "each listed row has its hit")
        assertEquals(SearchPlace.REQUEST_BODY, done.hits.hitsOf("r1-1:1")!!.single().place)
        assertEquals(io.yamsergey.dta.present.network.HitTab.REQUEST, done.hits.tabOf(done.hits.hitsOf("r1-1:1")!!), "the detail opens on the Payload tab")
        // The same filters and no words: searched again, which must not drop the stream's hits.
        network.setQuery("body:main ")
        delay(200)
        assertEquals(2, network.state.value.hits.total, "hits stay")
        daemon.ops.decide = { _, e -> EntryDecision(true, hits = listOf(bodyHit(e.id))) to false }
        daemon.ops.events.emit(NetworkEntryEvent(http(9), EntryChange.CREATED))
        val live = await { s -> s.list.rows.any { it.id == "r1-1:9" } && s.hits.total == 3 }
        assertEquals(SearchPlace.REQUEST_BODY, live.hits.hitsOf("r1-1:9")!!.single().place, "a live entry's body hit joins too")
    }
}
