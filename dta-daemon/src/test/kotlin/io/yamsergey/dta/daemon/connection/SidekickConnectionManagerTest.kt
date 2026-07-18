package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.adb.AppNotRunningException
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class FakeConnection(packageName: String) : AppConnection {
    override val hello = HelloResult(PROTOCOL_VERSION, "dta-sidekick", "0.1.0", packageName)
    override val events: SharedFlow<Event> = MutableSharedFlow()
    var open = true
    var pingHealthy = true
    var closeCount = 0

    override val isOpen: Boolean get() = open

    override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement {
        if (!open) throw ConnectionLostException("app disconnected")
        if (method == "ping" && !pingHealthy) throw ConnectionLostException("app disconnected")
        return JsonPrimitive("ok")
    }

    override suspend fun close() {
        open = false
        closeCount++
    }
}

private class FakeForwards {
    val active = mutableMapOf<ConnectionKey, Int>()
    val removed = mutableListOf<Int>()
    var refuse = false
    private var nextPort = 40000

    fun forward(key: ConnectionKey): Int {
        if (refuse) throw AppNotRunningException("${key.packageName} is not running on ${key.serial} — launch it first")
        val port = nextPort++
        active[key] = port
        return port
    }

    fun remove(serial: String, port: Int) {
        removed += port
        active.entries.removeIf { it.value == port }
    }
}

class SidekickConnectionManagerTest {

    private val key = ConnectionKey("emulator-5554", "com.example.fixture")

    private class Harness {
        val forwards = FakeForwards()
        var dialCount = AtomicInteger(0)
        var dialDelay: Duration = Duration.ZERO
        var dialShouldFail = false
        var built = mutableListOf<FakeConnection>()
        var now = 0L

        val manager = SidekickConnectionManager(
            forwardOps = ForwardOps(
                forward = { forwards.forward(it) },
                remove = { serial, port -> forwards.remove(serial, port) },
            ),
            connector = { key, _ ->
                dialCount.incrementAndGet()
                if (dialDelay > Duration.ZERO) delay(dialDelay)
                if (dialShouldFail) throw ConnectException("cannot reach sidekick in ${key.packageName} — restart the app and retry")
                FakeConnection(key.packageName).also { built += it }
            },
            probe = { connection -> runCatching { connection.request("ping", null, 2.seconds) }.isSuccess },
            cooldown = 2.seconds,
            clock = { now },
        )
    }

    @Test
    fun `connect creates a forward and returns a live connection`() = runBlocking {
        val h = Harness()
        val connection = h.manager.connect(key)
        assertEquals("com.example.fixture", connection.hello.packageName)
        assertEquals(1, h.forwards.active.size)
        assertEquals(1, h.dialCount.get())
    }

    @Test
    fun `second connect reuses the managed connection after a successful probe`() = runBlocking {
        val h = Harness()
        val first = h.manager.connect(key)
        val second = h.manager.connect(key)
        assertSame(first, second)
        assertEquals(1, h.dialCount.get())
    }

    @Test
    fun `failed probe on reuse tears down fully and dials fresh`() = runBlocking {
        val h = Harness()
        val first = h.manager.connect(key) as FakeConnection
        first.pingHealthy = false

        val second = h.manager.connect(key)

        assertTrue(first !== second)
        assertEquals(2, h.dialCount.get())
        assertEquals(1, h.forwards.removed.size, "stale forward must be released")
        assertEquals(1, h.forwards.active.size, "fresh forward must exist")
        assertEquals(1, first.closeCount, "stale connection must be closed")
    }

    @Test
    fun `concurrent connects for one key coalesce into a single dial`() = runBlocking {
        val h = Harness()
        h.dialDelay = 0.2.seconds
        val connections = (1..5).map { async { h.manager.connect(key) } }.awaitAll()
        assertEquals(1, h.dialCount.get(), "5 concurrent connects must share one dial")
        connections.forEach { assertSame(connections.first(), it) }
    }

    @Test
    fun `failed connect releases the forward and leaves no half-open state`() = runBlocking {
        val h = Harness()
        h.dialShouldFail = true
        assertFailsWith<ConnectException> { h.manager.connect(key) }
        assertTrue(h.forwards.active.isEmpty(), "no forward may outlive a failed connect")
    }

    @Test
    fun `retry within the cooldown returns the cached failure without redialing`() = runBlocking {
        val h = Harness()
        h.dialShouldFail = true
        assertFailsWith<ConnectException> { h.manager.connect(key) }

        h.now += 500 // inside 2s cooldown
        assertFailsWith<ConnectException> { h.manager.connect(key) }
        assertEquals(1, h.dialCount.get(), "cooldown must suppress the herd")

        h.now += 2_000 // past cooldown
        h.dialShouldFail = false
        val connection = h.manager.connect(key)
        assertEquals("com.example.fixture", connection.hello.packageName)
        assertEquals(2, h.dialCount.get())
    }

    @Test
    fun `app-not-running preflight failure propagates its actionable message`() = runBlocking {
        val h = Harness()
        h.forwards.refuse = true
        val e = assertFailsWith<AppNotRunningException> { h.manager.connect(key) }
        assertTrue("launch it first" in e.message!!)
    }

    @Test
    fun `close releases connection and forward`() = runBlocking {
        val h = Harness()
        val connection = h.manager.connect(key) as FakeConnection
        h.manager.close(key)
        assertEquals(1, connection.closeCount)
        assertTrue(h.forwards.active.isEmpty())

        // Next connect dials fresh.
        h.manager.connect(key)
        assertEquals(2, h.dialCount.get())
    }

    @Test
    fun `connections to different keys are independent`() = runBlocking {
        val h = Harness()
        val otherKey = ConnectionKey("emulator-5554", "com.example.other")
        val a = h.manager.connect(key)
        val b = h.manager.connect(otherKey)
        assertTrue(a !== b)
        assertEquals(2, h.forwards.active.size)
    }
}
