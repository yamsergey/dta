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
        assertEquals("r1-1", loaded.run)
        daemon.ops.events.emit(NetworkEntryEvent(http(10, HttpState.PENDING), EntryChange.CREATED))
        await { it.list.rows.last().id == "r1-1:10" }
        daemon.ops.events.emit(NetworkEntryEvent(http(10), EntryChange.UPDATED))
        val updated = await { it.list.rows.last().entry["state"] == JsonPrimitive("complete") }
        assertEquals(4, updated.list.rows.size)
    }

    @Test
    fun `a filter change is a new query with exactly those parameters`() = runBlocking<Unit> {
        session.start()
        await { it.list.rows.size == 3 }
        network.setFilter("status", JsonPrimitive("4xx"))
        withTimeout(5_000) { while (daemon.ops.queries.none { it.second.filter?.status == "4xx" }) kotlinx.coroutines.delay(20) }
        assertEquals("4xx", network.state.value.filters.params()["status"]!!.let { (it as JsonPrimitive).content })
        network.removeFilter("status")
        withTimeout(5_000) { while (daemon.ops.queries.last().second.filter?.status != null) kotlinx.coroutines.delay(20) }
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
