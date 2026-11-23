package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HistoryScope
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D-079 (071 FR-001/FR-003/FR-004, decision 2026-11-23): a plain `network.watch` delivers only entries of the
 * app's current run; entries of earlier runs that reach the daemon later (a fresh daemon catching up the
 * device journal) are not delivered. `archive` widens it to every run. The answer states the scope.
 */
class WatchRunScopeTest {
    private val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-watch-scope").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations()
    private val server = DaemonServer(
        identity, state, OperationDispatcher(fake, Selector(fake::devices, fake::apps)), fake, Selector(fake::devices, fake::apps),
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = {},
    ).also { it.start() }

    @AfterTest
    fun tearDown() = server.stop(null)

    private fun http(run: String, n: Int) = NetworkEntryEvent(
        HttpEntry("$run:$n", run, n.toLong(), "okhttp3", method = "GET", url = "https://api.example.test/$run/$n", state = HttpState.COMPLETE, status = 200),
        EntryChange.CREATED,
    )

    private fun JsonObject.id() = this["event"]?.jsonObject?.get("entry")?.jsonObject?.get("id")?.jsonPrimitive?.content

    /** Subscribes with [params], applies a catch-up that mixes an earlier run (r1) with the current one (r2), then a live one. */
    private fun watch(params: Map<String, JsonPrimitive>): Pair<JsonObject, List<String>> = runBlocking {
        val client = DaemonClient.connect(server.port, state.token()!!, ClientKind.CLI, identity)
        val answers = CopyOnWriteArrayList<JsonObject>()
        val events = CopyOnWriteArrayList<JsonObject>()
        val job = launch {
            client.networkEvents(JsonObject(params)).collect { if (it["event"] != null) events += it else answers += it }
        }
        withTimeout(5_000) { while (answers.none { it["selection"] != null }) delay(20) }
        listOf(http("r1", 1), http("r2", 1), http("r1", 2), http("r1", 3), http("r2", 2), http("r2", 3)).forEach { fake.events.emit(it) }
        withTimeout(10_000) { while (events.none { it.id() == "r2:3" }) delay(20) }
        delay(300)
        job.cancel()
        client.close()
        answers.first { it["selection"] != null }["selection"]!!.jsonObject to events.mapNotNull { it.id() }
    }

    private fun scopeOf(selection: JsonObject): HistoryScope? =
        DtaJson.decodeFromJsonElement(Selected.serializer(), selection).result.jsonObject["scope"]
            ?.let { DtaJson.decodeFromJsonElement(HistoryScope.serializer(), it) }

    @Test
    fun `a plain watch delivers only the current run, though earlier runs arrive later`() {
        fake.currentRun = "r2"
        val (answer, ids) = watch(emptyMap())
        assertEquals(listOf("r2:1", "r2:2", "r2:3"), ids)
        val scope = assertNotNull(scopeOf(answer), "the watch states its scope: $answer")
        assertFalse(scope.archive)
        assertEquals(2, scope.outside, "more history exists and is said")
    }

    /** As the CLI and the plugin subscribe: a live view, here one whose decisions are not made by the history. */
    @Test
    fun `a watching view is scoped the same way`() {
        fake.currentRun = "r2"
        val (_, ids) = watch(mapOf("view" to JsonPrimitive("watch")))
        assertEquals(listOf("r2:1", "r2:2", "r2:3"), ids)
    }

    @Test
    fun `with archive every run is delivered, and the scope says so`() {
        fake.currentRun = "r2"
        val (answer, ids) = watch(mapOf("archive" to JsonPrimitive(true)))
        assertEquals(listOf("r1:1", "r2:1", "r1:2", "r1:3", "r2:2", "r2:3"), ids)
        assertTrue(assertNotNull(scopeOf(answer)).archive)
    }

    /** The other side: when the current run is not known, nothing is held back. */
    @Test
    fun `without a known current run nothing is held back`() {
        fake.currentRun = null
        val (_, ids) = watch(emptyMap())
        assertEquals(listOf("r1:1", "r2:1", "r1:2", "r1:3", "r2:2", "r2:3"), ids)
    }
}
