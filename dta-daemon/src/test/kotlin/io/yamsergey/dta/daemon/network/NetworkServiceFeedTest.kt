package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 068 (research R7): watchers get what the archive takes, from the same feed. */
class NetworkServiceFeedTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun http(app: FakeJournalApp, n: Int, state: HttpState = HttpState.COMPLETE) =
        HttpEntry("r1-1:$n", "r1-1", 1_000L + n, "okhttp3", method = "GET", url = "https://api.example.test/v1/$n", state = state).also { app.record(it) }

    private suspend fun watch(service: NetworkService, seen: MutableList<NetworkEntryEvent>, block: suspend () -> Unit) = kotlinx.coroutines.coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { service.events { subscribed.complete(Unit) }.collect { seen += it } }
        withTimeout(5_000) { subscribed.await() }
        block()
        withTimeout(5_000) { while (seen.size < 3) kotlinx.coroutines.delay(10) }
        job.cancel()
    }

    @Test
    fun `entries the archive takes go to watchers, created then updated, and the app's own events do not`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        val host = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }
        val service = NetworkService(host = host) { app }
        host.openFeed()
        val seen = CopyOnWriteArrayList<NetworkEntryEvent>()
        watch(service, seen) {
            val pending = http(app, 1, HttpState.PENDING)
            // The app's own event (an older path): not passed on, the feed brings it.
            (app.events as MutableSharedFlow<Event>).emit(Event(NetworkEntryEvent.NAME, 1, DtaJson.encodeToJsonElement(NetworkEntryEvent.serializer(), NetworkEntryEvent(pending, EntryChange.CREATED))))
            app.record(pending.copy(state = HttpState.COMPLETE, status = 200))
            http(app, 2)
            host.apply(app.batch(0))
        }
        assertEquals(listOf("r1-1:1" to EntryChange.CREATED, "r1-1:1" to EntryChange.UPDATED, "r1-1:2" to EntryChange.CREATED), seen.map { it.entry.id to it.change })
        assertEquals(HttpState.COMPLETE, (seen[1].entry as HttpEntry).state)
    }

    @Test
    fun `a history kept in the app's memory only still comes from the app's events`() = runBlocking<Unit> {
        val app = FakeJournalApp(persisted = false)
        val host = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }
        val service = NetworkService(host = host) { app }
        host.openFeed()
        val seen = CopyOnWriteArrayList<NetworkEntryEvent>()
        watch(service, seen) {
            repeat(3) { n ->
                val e = http(app, n + 1)
                (app.events as MutableSharedFlow<Event>).emit(Event(NetworkEntryEvent.NAME, n.toLong(), DtaJson.encodeToJsonElement(NetworkEntryEvent.serializer(), NetworkEntryEvent(e, EntryChange.CREATED))))
            }
        }
        assertEquals(listOf("r1-1:1", "r1-1:2", "r1-1:3"), seen.map { it.entry.id })
    }
}
