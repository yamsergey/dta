package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 066 T027: a clear first copies what this computer does not have yet (research R6). */
class NetworkServiceClearTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()
    private var now = 100_000L

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun http(app: FakeJournalApp, n: Int, body: String) =
        HttpEntry("${app.runId}:$n", app.runId, now + n, "okhttp3", method = "GET", url = "https://api/x/$n", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, body.length.toLong(), completeness = Completeness.COMPLETE))
            .also { e -> app.record(e); app.bodies[e.id to BodyPart.RESPONSE] = body.toByteArray() }

    private fun service(app: FakeJournalApp): Pair<NetworkService, HostStore> {
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { app }
        return NetworkService(host = host) { app } to store
    }

    @Test
    fun `entries and bodies not here yet are copied before the device forgets them`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(4) { http(app, it + 1, "body-$it") }
        val (service, _) = service(app)
        val result = service.clear()
        assertNull(result.notKept, "everything was kept")
        assertEquals(4, result.clearedEntries)
        service.list(NetworkQuery()) // the next answer sees the new journal and archives the old one
        val archived = service.list(NetworkQuery(archiveScope = ArchiveScope(all = true)))
        assertEquals(4, archived.entries.size)
        assertNotNull(service.body("r1-1:2", BodyPart.RESPONSE).text?.takeIf { it == "body-1" }, "the body is on this computer")
    }

    /**
     * D-006 (011 US-1 scenario 1): right after a clear the cleared history is an archived epoch,
     * so deleting the older of two cleared epochs that share a body leaves the newer one whole.
     */
    @Test
    fun `a clear archives at once, and deleting the older epoch leaves the newer one and their shared body`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now++ }) { app }
        val service = NetworkService(host = host) { app }
        val shared = "s".repeat(70_000)
        http(app, 1, shared)
        service.list(NetworkQuery())
        CopyWorker(host).runOnce()
        service.clear()
        app.runId = "r2-1"
        http(app, 1, shared)
        service.list(NetworkQuery())
        CopyWorker(host).runOnce()
        service.clear()
        val epochs = store.listing().devices.single().apps.single().installations.flatMap { it.epochs }.filter { !it.live }.sortedBy { it.closedMs }
        assertEquals(2, epochs.size, "both cleared histories are archived as soon as they are cleared: $epochs")
        val (older, newer) = epochs
        store.delete(io.yamsergey.dta.daemon.store.DeleteTarget.Epoch(older.id))
        val left = service.list(NetworkQuery(archiveScope = ArchiveScope(epoch = newer.id)))
        assertEquals(listOf("r2-1:1"), left.entries.map { it.id }, "the newer epoch is untouched")
        assertEquals(shared, service.body("r2-1:1", BodyPart.RESPONSE).text, "the body both shared is still here")
    }

    /** D-005: a body the copy worker is copying when the clear starts is waited for, not counted lost at once. */
    @Test
    fun `a body the copy worker is copying is waited for before the clear`() = runBlocking<Unit> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val app = object : FakeJournalApp() {
            override suspend fun requestFull(method: String, params: kotlinx.serialization.json.JsonObject?, timeout: kotlin.time.Duration?): io.yamsergey.dta.daemon.connection.RpcResult {
                if (method == "network_body_range") { started.complete(Unit); gate.await() }
                return super.requestFull(method, params, timeout)
            }
        }
        // Bodies above their preview, so each one is fetched.
        val big = "x".repeat(70_000)
        HttpEntry("${app.runId}:1", app.runId, now + 1, "okhttp3", method = "GET", url = "https://api/x/1", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, big.length.toLong(), completeness = Completeness.COMPLETE))
            .also { e -> app.record(e); app.bodies[e.id to BodyPart.RESPONSE] = big.toByteArray() }
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }
        val service = NetworkService(host = host) { app }
        service.list(NetworkQuery())
        assertEquals(1, host.pendingBodies().size)
        val worker = launch(kotlinx.coroutines.Dispatchers.Default) { CopyWorker(host).runOnce() }
        started.await()
        val clear = async(kotlinx.coroutines.Dispatchers.Default) { service.clear(copyTimeoutMs = 10_000) }
        kotlinx.coroutines.delay(300)
        gate.complete(Unit)
        val result = clear.await()
        worker.join()
        assertNull(result.notKept, "the worker's copy finished well within the clear's time: ${result.notKept}")
    }

    @Test
    fun `what the copy could not take in time is counted and said`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(3) { http(app, it + 1, "body-$it") }
        val (service, _) = service(app)
        val result = service.clear(copyTimeoutMs = 0)
        val notKept = assertNotNull(result.notKept)
        assertEquals(3, notKept.bodies)
        assertTrue(notKept.reason.contains("did not finish"), notKept.reason)
    }
}
