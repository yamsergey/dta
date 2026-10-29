package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** App connection state changes (feature 009, FR-017; contracts/daemon-protocol.md). */
class AppConnectionEventsTest {

    private class Link(pkg: String) : AppConnection {
        override val hello = HelloResult(PROTOCOL_VERSION, "dta-sidekick", "0.10.0", pkg)
        override val events: SharedFlow<Event> = MutableSharedFlow()
        var open = true
        var answers = true
        override val isOpen: Boolean get() = open
        override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement {
            if (!open || !answers) throw ConnectionLostException("app disconnected")
            return JsonPrimitive("ok")
        }
        override suspend fun close() { open = false }
    }

    private class Harness {
        val links = mutableMapOf<ConnectionKey, Link>()
        var refuse: String? = null
        var now = 0L
        val manager = SidekickConnectionManager(
            forwardOps = ForwardOps(forward = { 40000 }, remove = { _, _ -> }),
            connector = { key, _ ->
                refuse?.let { throw VersionIncompatibleException(it) }
                Link(key.packageName).also { links[key] = it }
            },
            probe = { c -> runCatching { c.request("ping", null, 1.seconds) }.isSuccess },
            cooldown = 2.seconds,
            clock = { now },
        )

        /** Runs [block], then returns the events it produced. */
        suspend fun events(block: suspend () -> Unit): List<AppConnectionEvent> {
            val seen = mutableListOf<AppConnectionEvent>()
            val collector = kotlinx.coroutines.GlobalScope.async(start = CoroutineStart.UNDISPATCHED) { manager.changes.collect { seen += it } }
            block()
            kotlinx.coroutines.delay(50)
            collector.cancel()
            return seen
        }
    }

    private val key = ConnectionKey("emulator-5554", "com.example.shop")

    @Test
    fun `a new connection is connected once, reuse says nothing`() = runBlocking {
        val h = Harness()
        val events = h.events {
            h.manager.connect(key)
            h.manager.connect(key)
        }
        assertEquals(listOf(AppConnectionEvent("emulator-5554", "com.example.shop", "connected")), events)
    }

    @Test
    fun `a version mismatch is refused with the cause, once while it lasts`() = runBlocking {
        val h = Harness()
        h.refuse = "sidekick speaks protocol 2, this daemon 1 — update DTA"
        val events = h.events {
            assertFailsWith<VersionIncompatibleException> { h.manager.connect(key) }
            h.now += 5_000
            assertFailsWith<VersionIncompatibleException> { h.manager.connect(key) }
        }
        assertEquals(listOf(AppConnectionEvent("emulator-5554", "com.example.shop", "refused", "sidekick speaks protocol 2, this daemon 1 — update DTA")), events)
    }

    @Test
    fun `a link that stops answering is lost with the cause, then connected again`() = runBlocking {
        val h = Harness()
        h.manager.connect(key)
        h.links.getValue(key).answers = false
        val events = h.events { h.manager.connect(key) }
        assertEquals(listOf("lost", "connected"), events.map { it.state })
        assertTrue(events[0].reason!!.isNotBlank())
    }

    @Test
    fun `an ended process is ended once and the link released`() = runBlocking {
        val h = Harness()
        h.manager.connect(key)
        val events = h.events {
            h.manager.ended(key)
            h.manager.ended(key)
        }
        assertEquals(listOf(AppConnectionEvent("emulator-5554", "com.example.shop", "ended")), events)
        assertEquals(false, h.links.getValue(key).open)
        assertEquals(emptyList(), h.manager.openKeys())
    }

    @Test
    fun `the watcher connects to apps that appear and ends those that disappear`() = runBlocking {
        val h = Harness()
        var running = listOf(key)
        val watcher = AppConnectionWatcher(instances = { serial -> running.filter { serial == null || it.serial == serial } }, manager = h.manager, interval = 20.milliseconds)
        val events = withTimeout(5.seconds) {
            val collected = async { watcher.events("emulator-5554").take(2).toList() }
            kotlinx.coroutines.delay(100)
            running = emptyList()
            collected.await()
        }
        assertEquals(listOf("connected", "ended"), events.map { it.state })
    }

    @Test
    fun `the watcher states a link lost while the app keeps running`() = runBlocking {
        val h = Harness()
        val watcher = AppConnectionWatcher(instances = { listOf(key) }, manager = h.manager, interval = 20.milliseconds)
        val events = withTimeout(5.seconds) {
            val collected = async { watcher.events(null).take(3).toList() }
            kotlinx.coroutines.delay(100)
            h.links.getValue(key).open = false
            collected.await()
        }
        assertEquals(listOf("connected", "lost", "connected"), events.map { it.state })
    }

    @Test
    fun `the watcher only reports the device it watches`() = runBlocking {
        val h = Harness()
        val other = ConnectionKey("emulator-5556", "com.example.shop")
        val watcher = AppConnectionWatcher(instances = { serial -> listOf(key, other).filter { serial == null || it.serial == serial } }, manager = h.manager, interval = 20.milliseconds)
        val firstForOther = async { withTimeout(5.seconds) { watcher.events("emulator-5556").take(1).toList() } }
        // A watcher of every device connects emulator-5554 meanwhile; the first must not see it.
        // (emulator-5556 was connected before it started: a late watcher learns that from apps.list.)
        val firstForAll = async { withTimeout(5.seconds) { watcher.events(null).take(1).toList() } }
        assertEquals(listOf("emulator-5556"), firstForOther.await().map { it.device })
        assertEquals(listOf("emulator-5554"), firstForAll.await().map { it.device })
    }
}
