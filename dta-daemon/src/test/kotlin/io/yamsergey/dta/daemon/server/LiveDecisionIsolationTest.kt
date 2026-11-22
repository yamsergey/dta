package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.VersionIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 071 B3 (FR-009, research R8): a live decision that runs long holds its own subscription only. */
class LiveDecisionIsolationTest {
    private val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-live").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations().apply { decides = true }
    private val server = DaemonServer(
        identity, state, OperationDispatcher(fake, Selector(fake::devices, fake::apps)), fake, Selector(fake::devices, fake::apps),
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = {},
    ).also { it.start() }

    @AfterTest
    fun tearDown() { server.stop(null); fake.searches.close() }

    private fun connect() = runBlocking { DaemonClient.connect(server.port, state.token()!!, ClientKind.IDE, identity) }

    private fun http(id: String) = NetworkEntryEvent(HttpEntry(id, "r1", 1, "okhttp3", method = "GET", url = "https://api.example.test/$id", state = HttpState.COMPLETE), EntryChange.CREATED)

    private fun watch(client: DaemonClient, view: String) = client.networkEvents(JsonObject(mapOf("view" to JsonPrimitive(view), "q" to JsonPrimitive("body:/(x+x+)+y/"))))

    @Test
    fun `a stuck decision delays neither the feed nor another watcher, and ends with its subscription`() = runBlocking<Unit> {
        val stuckClient = connect()
        val other = connect()
        val stuck = async { watch(stuckClient, "stuck").filter { it["event"] != null }.take(1).toList() }
        val fine = async { watch(other, "fine").filter { it["event"] != null }.take(3).toList() }
        delay(500)
        // The feed's own emit never waits for a watcher.
        val started = System.nanoTime()
        fake.events.emit(http("slow"))
        repeat(3) { fake.events.emit(http("e$it")) }
        val emitMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(emitMs < 50, "the feed waited $emitMs ms")
        val got = withTimeout(3_000) { fine.await() }
        assertEquals(listOf("slow", "e0", "e1"), got.map { ((it["event"] as JsonObject)["entry"] as JsonObject)["id"].let { e -> (e as JsonPrimitive).content } })
        assertTrue(!stuck.isCompleted, "the stuck watcher is still deciding")
        stuck.cancel()
        withTimeout(2_000) { while (fake.decisionsStopped.get() == 0) delay(10) }
        assertEquals(1, fake.decisionsStopped.get())
        stuckClient.close(); other.close()
    }
}
