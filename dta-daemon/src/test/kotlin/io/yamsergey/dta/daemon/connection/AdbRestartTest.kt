package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.daemon.discovery.Device
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * D-038: the adb server restarts under a live daemon (`adb kill-server`, Android Studio). The device
 * is gone for a moment, comes back offline, then online; every forward died with the server, and
 * the app's link with it. The daemon must find the device and the app again by itself, on a new
 * forward, within seconds — as a fresh start would.
 */
class AdbRestartTest {

    /** A fake adb whose server can be killed and brought back, step by step. */
    private class FakeAdb {
        enum class Server { UP, GONE, DEVICE_OFFLINE }
        @Volatile var server = Server.UP
        private val ports = AtomicInteger(40000)
        val forwards = CopyOnWriteArrayList<Int>()

        fun run(args: List<String>): AdbExec.Result = when {
            args == listOf("devices", "-l") -> when (server) {
                Server.GONE -> AdbExec.Result(0, "List of devices attached\n\n", "* daemon not running; starting now at tcp:5037\n")
                Server.DEVICE_OFFLINE -> AdbExec.Result(0, "List of devices attached\nemulator-5554          offline transport_id:1\n\n", "")
                Server.UP -> AdbExec.Result(0, "List of devices attached\nemulator-5554          device product:sdk model:Pixel transport_id:1\n\n", "")
            }
            "shell" in args && "/proc/net/unix" in args -> when (server) {
                Server.UP -> AdbExec.Result(0, "0000: 00000002 00000000 00010000 0001 01 1234 @dta_sidekick_com.example.shop\n", "")
                Server.DEVICE_OFFLINE -> AdbExec.Result(1, "", "adb: device offline\n")
                Server.GONE -> AdbExec.Result(1, "", "adb: device 'emulator-5554' not found\n")
            }
            "pidof" in args -> AdbExec.Result(1, "", "")
            "forward" in args && "--remove" in args -> AdbExec.Result(0, "", "")
            "forward" in args -> if (server == Server.UP) {
                AdbExec.Result(0, "${ports.incrementAndGet().also { forwards += it }}\n", "")
            } else {
                AdbExec.Result(1, "", "adb: device offline\n")
            }
            else -> AdbExec.Result(1, "", "unexpected: $args")
        }
    }

    private class Link(pkg: String, val port: Int) : AppConnection {
        override val hello = HelloResult(PROTOCOL_VERSION, "dta-sidekick", "0.10.0", pkg)
        override val events: SharedFlow<Event> = MutableSharedFlow()
        @Volatile var open = true
        override val isOpen: Boolean get() = open
        override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement {
            if (!open) throw ConnectionLostException("app disconnected")
            return JsonPrimitive("ok")
        }
        override suspend fun close() { open = false }
    }

    @Test
    fun `after the adb server restarts the device is online again and the app reconnects on a new forward`() = runBlocking {
        val adb = FakeAdb()
        val discovery = SidekickDiscovery(adb::run)
        val forwards = PortForwards(adb::run)
        val links = CopyOnWriteArrayList<Link>()
        val manager = SidekickConnectionManager(
            forwardOps = ForwardOps(forward = { forwards.forward(it) }, remove = { s, p -> forwards.remove(s, p) }),
            connector = { key, port -> Link(key.packageName, port).also { links += it } },
            probe = { c -> runCatching { c.request("ping", null, 1.seconds) }.isSuccess },
            cooldown = 200.milliseconds,
        )
        val watcher = AppConnectionWatcher(
            instances = { serial -> discovery.listInstances().filter { serial == null || it.serial == serial }.map { ConnectionKey(it.serial, it.packageName) } },
            manager = manager,
            interval = 50.milliseconds,
        )
        val seen = CopyOnWriteArrayList<AppConnectionEvent>()
        val events = watcher.events("emulator-5554").onEach { seen += it }
        val watching = async { events.first { false } }
        try {
            withTimeout(5.seconds) { while (seen.none { it.state == "connected" }) kotlinx.coroutines.delay(20) }
            val before = links.single()

            // adb kill-server: the forward and the app's link die with it; the device is gone.
            adb.server = FakeAdb.Server.GONE
            before.open = false
            kotlinx.coroutines.delay(300)
            // adb start-server: the emulator comes back offline first, then online.
            adb.server = FakeAdb.Server.DEVICE_OFFLINE
            kotlinx.coroutines.delay(300)
            assertEquals(listOf(Device("emulator-5554", Device.State.OFFLINE)), discovery.listDevices())
            val restartedAt = seen.size
            adb.server = FakeAdb.Server.UP

            val started = System.nanoTime()
            withTimeout(3.seconds) { while (seen.drop(restartedAt).none { it.state == "connected" }) kotlinx.coroutines.delay(20) }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf(Device("emulator-5554", Device.State.ONLINE)), discovery.listDevices())
            val after = links.last()
            assertTrue(after !== before && after.isOpen, "a new link was made")
            assertTrue(after.port != before.port, "on a new forward: ${before.port} -> ${after.port}")
            assertEquals(listOf(ConnectionKey("emulator-5554", "com.example.shop")), manager.openKeys())
            assertTrue(tookMs < 3_000, "reconnected in $tookMs ms")
            // Between: the app was said to be gone (or lost), never left in that state.
            val states = seen.map { it.state }
            assertEquals("connected", states.last(), "events: $states")
            assertTrue(states.drop(1).any { it == "ended" || it == "lost" }, "the drop was said: $states")
        } finally {
            watching.cancel()
        }
    }
}
