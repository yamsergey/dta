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
        val archived = service.list(NetworkQuery(archive = ArchiveScope(all = true)))
        assertEquals(4, archived.entries.size)
        assertNotNull(service.body("r1-1:2", BodyPart.RESPONSE).text?.takeIf { it == "body-1" }, "the body is on this computer")
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
