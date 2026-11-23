package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.network.FindEnd
import io.yamsergey.dta.protocol.network.FoundEvent
import io.yamsergey.dta.protocol.network.FoundItem
import io.yamsergey.dta.protocol.network.FindState
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.SearchStoppedException
import io.yamsergey.dta.protocol.network.StopReason
import io.yamsergey.dta.protocol.network.find
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 071 B2 (contracts/find.md): `network.find` on the wire — states, credit, stop, a client gone, request.cancel. */
class NetworkFindServerTest {
    private val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-find").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations()
    private val server = DaemonServer(
        identity, state, OperationDispatcher(fake, Selector(fake::devices, fake::apps)), fake, Selector(fake::devices, fake::apps),
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = {},
    ).also { it.start() }

    @AfterTest
    fun tearDown() { server.stop(null); fake.searches.close() }

    private fun connect() = runBlocking { DaemonClient.connect(server.port, state.token()!!, ClientKind.IDE, identity) }

    private fun item(n: Int) = FoundItem(HttpEntry("r1:$n", "r1", n.toLong(), "okhttp3", method = "GET", url = "https://api.example.test/$n", state = HttpState.COMPLETE))

    private fun event(o: JsonObject) = DtaJson.decodeFromJsonElement(FoundEvent.serializer(), o)

    private val params = JsonObject(mapOf("q" to JsonPrimitive("token")))

    /** A walk stuck in one runaway match; [ended] counts down when it has stopped. */
    private fun runaway(ended: CountDownLatch, endedAt: AtomicLong = AtomicLong()) = { stop: io.yamsergey.dta.protocol.network.PatternStop, _: (FoundItem) -> Unit ->
        try {
            io.yamsergey.dta.protocol.network.BodyPattern.find(java.util.regex.Pattern.compile("(x+x+)+y"), "x".repeat(1 shl 20)) { if (stop.stopped()) throw SearchStoppedException() }
            FindEnd(null, null)
        } finally {
            endedAt.set(System.nanoTime()); ended.countDown()
        }
    }

    @Test
    fun `a search answers its stream, then searching with its scope, its rows, and done last`() = runBlocking<Unit> {
        fake.findWalk = { _, onItem -> (3 downTo 1).forEach { onItem(item(it)) }; FindEnd(null, listOf("bodies stored cut, searched as far as stored (1)")) }
        val client = connect()
        val events = withTimeout(5_000) { client.find(params).toList() }
        assertTrue(events.first().containsKey("selection"))
        val found = events.drop(1).map(::event)
        assertEquals(FindState.SEARCHING, found.first().state)
        assertEquals("r1", found.first().scope!!.run!!.runId)
        assertEquals(listOf("r1:3", "r1:2", "r1:1"), found.flatMap { it.items.orEmpty() }.map { it.entry.id })
        assertEquals(FindState.DONE, found.last().state)
        assertEquals(listOf("bodies stored cut, searched as far as stored (1)"), found.last().notSearched)
        assertEquals("token", fake.findQuery!!.q)
        client.close()
    }

    @Test
    fun `a stop ends a runaway search within 200 ms and says stopped, never done`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        val endedAt = AtomicLong()
        fake.findWalk = runaway(ended, endedAt)
        val client = connect()
        val events = mutableListOf<JsonObject>()
        var stream: String? = null
        val job = async {
            client.find(params, grant = { 0 }).collect { e ->
                events += e
                if (stream == null) stream = ((e["selection"] as? JsonObject)?.get("result") as? JsonObject)?.get("stream")?.let { (it as JsonPrimitive).content }
            }
        }
        delay(400)
        val at = System.nanoTime()
        client.request(io.yamsergey.dta.protocol.ops.DaemonMethods.NETWORK_FIND_STOP, JsonObject(mapOf("stream" to JsonPrimitive(stream!!))))
        assertTrue(ended.await(2, TimeUnit.SECONDS))
        val tookMs = (endedAt.get() - at) / 1_000_000
        assertTrue(tookMs <= 200, "the walk ended $tookMs ms after the stop")
        withTimeout(5_000) { job.await() }
        val last = event(events.last())
        assertEquals(FindState.STOPPED, last.state)
        assertEquals(StopReason.STOP, last.reason)
        assertTrue(events.drop(1).map(::event).none { it.state == FindState.DONE })
        client.close()
    }

    @Test
    fun `cancelling the flow stops the search on the daemon`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        fake.findWalk = runaway(ended)
        val client = connect()
        val job = async { client.find(params).collect {} }
        delay(300)
        job.cancel()
        // Waited without holding the thread the client's cancellation runs on.
        assertTrue(kotlinx.coroutines.withTimeoutOrNull(2_000) { while (ended.count > 0) delay(10) } != null, "the walk went on after its client let go")
        client.close()
    }

    @Test
    fun `a client that goes away stops its searches`() = runBlocking<Unit> {
        val ended = CountDownLatch(1)
        fake.findWalk = runaway(ended)
        val client = connect()
        async { runCatching { client.find(params).collect {} } }
        delay(300)
        client.close()
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the walk went on after its client closed")
    }

    @Test
    fun `a limit ends with limit and the cursor`() = runBlocking<Unit> {
        fake.findWalk = { _, onItem -> onItem(item(9)); FindEnd("r1:9", null) }
        val client = connect()
        val last = event(withTimeout(5_000) { client.find(JsonObject(params + ("limit" to JsonPrimitive(1)))).toList() }.last())
        assertEquals(FindState.LIMIT, last.state)
        assertEquals("r1:9", last.next)
        client.close()
    }

    @Test
    fun `a cancelled call is cancelled on the daemon too, and the same client goes on`() = runBlocking<Unit> {
        val client = connect()
        val call = async { client.call("network.list", JsonObject(mapOf("q" to JsonPrimitive("wait-forever")))) }
        delay(300)
        call.cancel()
        withTimeout(2_000) { while (fake.cancelledCalls.get() == 0) delay(10) }
        assertEquals(1, fake.cancelledCalls.get())
        // Both sides of the rule: another call of the same client answers.
        assertEquals("selection", client.call("network.list", JsonObject(emptyMap())).let { if (it.result is JsonObject) "selection" else "?" })
        client.close()
    }

    /**
     * D-049 (contracts/find.md): over the limit a search says `waiting` with its position first, and
     * `searching` with its scope when a place frees, so a viewer knows it runs. Before, `searching` came
     * before `waiting`, and nothing followed the admission.
     */
    @Test
    fun `a search over the limit says waiting, then searching with its scope once a place is free`() = runBlocking<Unit> {
        val ended = CountDownLatch(2)
        fake.findWalk = runaway(ended)
        val client = connect()
        fun streamOf(e: JsonObject) = ((e["selection"] as? JsonObject)?.get("result") as? JsonObject)?.get("stream")?.let { (it as JsonPrimitive).content }
        val streams = List(3) { java.util.concurrent.CopyOnWriteArrayList<JsonObject>() }
        val jobs = streams.map { events -> async { runCatching { client.find(params).collect { events += it } } }.also { delay(200) } }
        fun found(n: Int) = streams[n].filter { !it.containsKey("selection") }.map(::event)
        withTimeout(5_000) { while (found(2).isEmpty()) delay(10) }
        delay(300)
        assertEquals(listOf(FindState.WAITING), found(2).map { it.state }, "only waiting while over the limit")
        assertEquals(1, found(2).single().position)
        assertEquals(FindState.SEARCHING, found(0).first().state)
        client.request(io.yamsergey.dta.protocol.ops.DaemonMethods.NETWORK_FIND_STOP, JsonObject(mapOf("stream" to JsonPrimitive(streamOf(streams[0].first())!!))))
        withTimeout(2_000) { while (found(2).size < 2) delay(10) }
        val admitted = found(2)[1]
        assertEquals(FindState.SEARCHING, admitted.state, "admitted: searching")
        assertEquals("r1", admitted.scope?.run?.runId, "with its scope")
        listOf(1, 2).forEach { n -> client.request(io.yamsergey.dta.protocol.ops.DaemonMethods.NETWORK_FIND_STOP, JsonObject(mapOf("stream" to JsonPrimitive(streamOf(streams[n].first())!!)))) }
        withTimeout(5_000) { jobs.forEach { it.await() } }
        client.close()
    }
}
