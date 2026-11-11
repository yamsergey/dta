package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.protocol.layout.LayoutChanged
import io.yamsergey.dta.protocol.layout.ScreenSignal
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Feature 057: the daemon keeps the app's screen signal leased while someone watches. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayoutSignalWatchTest {
    private class App(val supported: Boolean = true) : AppConnection {
        override val hello = HelloResult(PROTOCOL_VERSION, "dta-sidekick", "0.10.0", "io.example")
        override val events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
        override var isOpen = true
        val leases = mutableListOf<Long>()
        override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement {
            check(method == ScreenSignal.METHOD)
            if (!supported) throw SidekickRpcException(ErrorCodes.METHOD_NOT_FOUND, "Unknown method: $method")
            leases += params!!["leaseMs"]!!.jsonPrimitive.long
            return buildJsonObject { put("watching", true); put("until", 0) }
        }
        override suspend fun close() { isOpen = false }
        fun changed(at: Long) = events.tryEmit(Event(ScreenSignal.APP_EVENT, at, DtaJson.encodeToJsonElement(LayoutChanged.serializer(), LayoutChanged(at, listOf("layout")))))
    }

    @Test
    fun `forwards changes and renews the lease while watched, stops it when the watcher goes`() = runTest {
        val app = App()
        val seen = mutableListOf<LayoutSignalWatch.Update>()
        val job = launch { LayoutSignalWatch(connect = { app }).watch().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(10_000L), app.leases)
        assertEquals(LayoutSignalWatch.Update.State(ScreenSignal.WATCHING), seen.first())
        app.changed(42)
        runCurrent()
        assertEquals(LayoutSignalWatch.Update.Changed(LayoutChanged(42, listOf("layout"))), seen.last())
        advanceTimeBy(5_001)
        assertEquals(listOf(10_000L, 10_000L), app.leases, "renewed every 5 s")
        job.cancel()
        runCurrent()
        assertEquals(0L, app.leases.last(), "the lease is ended when nobody watches")
    }

    @Test
    fun `an older sidekick is stated once`() = runTest {
        val updates = LayoutSignalWatch(connect = { App(supported = false) }).watch().take(1).toList()
        assertEquals(LayoutSignalWatch.Update.State(ScreenSignal.UNSUPPORTED, ScreenSignal.UNSUPPORTED_REASON), updates.single())
    }

    @Test
    fun `a lost app is stated and dialled again`() = runTest {
        val first = App()
        val second = App()
        var dials = 0
        val seen = mutableListOf<LayoutSignalWatch.Update>()
        val job = launch { LayoutSignalWatch(connect = { if (dials++ == 0) first else second }).watch().collect { seen += it } }
        runCurrent()
        first.isOpen = false
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(seen.any { it is LayoutSignalWatch.Update.State && it.state == ScreenSignal.RECONNECTING }, "$seen")
        assertEquals(listOf(10_000L), second.leases, "the new connection is leased")
        job.cancel()
    }
}
