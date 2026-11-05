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
    fun `the query line is the list's query, filters re-query and search words only search (040)`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 && it.watching }
        network.setQuery("status:4xx")
        until { daemon.ops.queries.any { it.second.q == "status:4xx" } }
        assertTrue(daemon.ops.searches.isEmpty(), "no search words, no search")
        val lists = daemon.ops.queries.size
        daemon.ops.searchHits = listOf(io.yamsergey.dta.protocol.network.EntryHits("r1-1:2", 0, listOf(
            io.yamsergey.dta.protocol.network.SearchHit("r1-1:2", io.yamsergey.dta.protocol.network.SearchPlace.URL, "api", 8, 11, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("api", 0, 3)),
        )))
        network.setQuery("status:4xx api")
        until { daemon.ops.searches.any { it.q == "status:4xx api" } }
        val hit = await { it.hits.total == 1 }
        assertEquals("– / 1", hit.hits.label)
        assertEquals(lists, daemon.ops.queries.size, "a search word does not re-query the list")
        network.nextHit()
        val selected = await { it.list.selectedId == "r1-1:2" }
        assertEquals("1 / 1", selected.hits.label)
        network.setQuery("")
        until { daemon.ops.queries.last().second.q == null }
        await { it.hits.total == 0 }
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

    @Test
    fun `the kind switch writes the line and queries WebSocket traffic only, then everything again`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 }
        network.setKind(io.yamsergey.dta.present.network.KindChoice.WEBSOCKET)
        until { daemon.ops.queries.any { it.second.q == "ws" } }
        assertEquals(io.yamsergey.dta.present.network.KindChoice.WEBSOCKET, network.state.value.line.kind)
        network.setKind(io.yamsergey.dta.present.network.KindChoice.ALL)
        until { daemon.ops.queries.last().second.q == null }
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
}
