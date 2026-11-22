package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonHello
import io.yamsergey.dta.protocol.ops.DaemonMethods
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.Message
import io.yamsergey.dta.protocol.rpc.Request
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-040: a plain live subscription whose client reads at full speed gets every event of a burst
 * (a feed batch or a catch-up page arrives as hundreds of entries at once), none marked
 * `network-events-dropped`. Only a client that stops reading falls behind, and that one is told;
 * the feed never waits for it.
 */
class LiveSubscriptionBurstTest {
    private val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-burst").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations()
    // The daemon's own buffer bound (DaemonMain does not set one).
    private val server = DaemonServer(
        identity, state, OperationDispatcher(fake, Selector(fake::devices, fake::apps)), fake, Selector(fake::devices, fake::apps),
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = {},
    ).also { it.start() }

    @AfterTest
    fun tearDown() = server.stop(null)

    private fun http(n: Int) = NetworkEntryEvent(
        HttpEntry("r1-1:$n", "r1-1", n.toLong(), "okhttp3", method = "POST", url = "https://api.example.test/echo?n=$n", state = HttpState.COMPLETE, status = 200),
        EntryChange.CREATED,
    )

    private fun JsonObject.id() = this["event"]?.jsonObject?.get("entry")?.jsonObject?.get("id")?.jsonPrimitive?.content

    /** Waits until [count] has not grown for a second (at most 30 s). */
    private suspend fun quiet(count: () -> Int) = withTimeout(30_000) {
        var last = -1
        while (count() != last) { last = count(); delay(1_000) }
    }

    @Test
    fun `a burst into a plain subscription read at full speed loses nothing and is never marked dropped`() = runBlocking<Unit> {
        val client = DaemonClient.connect(server.port, state.token()!!, ClientKind.IDE, identity)
        val burst = 2_000
        val events = CopyOnWriteArrayList<JsonObject>()
        val job = launch { client.networkEvents(JsonObject(emptyMap())).filter { it["event"] != null }.collect { events += it } }
        delay(500)
        // As a feed batch goes in: every entry at once, nobody waits.
        (1..burst).forEach { fake.events.emit(http(it)) }
        quiet { events.size }
        job.cancel()
        val marked = events.filter { it["degraded"] != null }
        assertTrue(marked.isEmpty(), "${marked.size} events marked dropped, first ${marked.first().id()}: ${marked.first()["degraded"]}")
        assertEquals((1..burst).map { "r1-1:$it" }, events.map { it.id() }, "every event, in order")
        client.close()
    }

    /** The other side of the rule: a client that stops reading is told it fell behind, and the feed never waited for it. */
    @Test
    fun `a client that stops reading is told it fell behind, and the feed does not wait for it`() = runBlocking<Unit> {
        val reading = AtomicBoolean(true)
        val texts = CopyOnWriteArrayList<String>()
        var held = 0L
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onOpen(webSocket: WebSocket) = webSocket.request(1)
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { texts += text.toString(); text.setLength(0) }
                synchronized(this) { if (reading.get()) webSocket.request(1) else held++ }
                return null
            }
            fun resume(ws: WebSocket) { reading.set(true); synchronized(this) { ws.request(held + Long.MAX_VALUE / 2); held = 0 } }
        }
        val ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer ${state.token()!!}")
            .buildAsync(URI("ws://127.0.0.1:${server.port}/"), listener).join()
        fun call(id: Long, method: String, params: JsonObject) =
            ws.sendText(DtaJson.encodeToString(Message.serializer(), Request(id, method, params)), true).join()
        call(1, DaemonMethods.HELLO, DtaJson.encodeToJsonElement(DaemonHello.serializer(), DaemonHello(ClientKind.IDE, identity)).jsonObject)
        call(2, DaemonMethods.NETWORK_SUBSCRIBE, JsonObject(emptyMap()))
        withTimeout(5_000) { while (texts.none { "\"subscription\"" in it }) delay(20) }
        reading.set(false)
        // Far more than every buffer on the way (socket, subscriber, decisions) holds.
        val burst = 40_000
        val started = System.nanoTime()
        (1..burst).forEach { fake.events.emit(http(it)) }
        val emitMs = (System.nanoTime() - started) / 1_000_000
        delay(1_000)
        listener.resume(ws)
        quiet { texts.size }
        assertTrue(emitMs < 10_000, "the feed waited for the client: $emitMs ms")
        val events = texts.filter { "network.entry" in it }
        assertTrue(events.size < burst, "a client that stopped reading cannot have got all ${events.size}")
        assertTrue(events.any { "network-events-dropped" in it }, "the client is told it fell behind")
        ws.abort()
    }
}
