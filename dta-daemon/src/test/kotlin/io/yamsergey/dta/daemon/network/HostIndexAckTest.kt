package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 066 T017: this computer tells the device what it has archived (research R1, R5, R11). */
class HostIndexAckTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()
    private var now = 100_000L

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store() = HostStore(dir).also { stores += it }

    private fun index(app: FakeJournalApp, store: HostStore = store()) =
        HostIndex(store, DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { app }

    private fun http(app: FakeJournalApp, n: Int, body: String? = null, state: HttpState = HttpState.COMPLETE) =
        HttpEntry("${app.runId}:$n", app.runId, now + n, "okhttp3", method = "GET", url = "https://api/x/$n", state = state, status = 200,
            responseBody = body?.let { BodyInfo(BodyPart.RESPONSE, it.length.toLong(), completeness = Completeness.COMPLETE) })
            .also { e -> app.record(e); body?.let { app.bodies[e.id to BodyPart.RESPONSE] = it.toByteArray() } }

    @Test
    fun `after a complete catch-up the device is told the cursor, and which entries wait for their bodies`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { http(app, it + 1, body = if (it % 2 == 0) "body-$it" else null) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        val ack = app.acks.single()
        assertEquals("j1", ack.journalId)
        assertEquals(s.hostId, ack.hostId)
        assertEquals("0:5", ack.through)
        assertEquals(listOf("r1-1:1", "r1-1:3", "r1-1:5"), ack.keep, "bodies not copied yet hold their entries")
        assertNull(host.list(NetworkQuery()).coverage.archiveHeld)
    }

    @Test
    fun `at most one acknowledgement every 10 s, and one after bodies were copied`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(3) { http(app, it + 1, body = "b$it") }
        val host = index(app)
        host.list(NetworkQuery())
        host.list(NetworkQuery())
        assertEquals(1, app.acks.size)
        now += 10_000
        host.list(NetworkQuery())
        assertEquals(2, app.acks.size)
        CopyWorker(host, sleep = {}).runOnce()
        assertEquals(3, app.acks.size)
        assertTrue(app.acks.last().keep.isEmpty(), "every body is here now")
    }

    @Test
    fun `no acknowledgement while the copy is far behind, and the answer says so`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(HostIndex.MAX_KEEP + 1) { http(app, it + 1, body = "b") }
        val host = index(app)
        assertEquals("copy-behind", host.list(NetworkQuery()).coverage.archiveHeld)
        assertTrue(app.acks.isEmpty())
    }

    @Test
    fun `an older library is told once, and said`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { acksUnsupported = true }
        http(app, 1)
        val host = index(app)
        assertEquals("old-library", host.list(NetworkQuery()).coverage.archiveHeld)
        now += 20_000
        host.list(NetworkQuery())
        assertEquals(1, app.requests.count { it == "network_archived" })
    }

    @Test
    fun `entries kept here only, and those another computer took`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(6) { http(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        app.entriesOverride = 2
        app.archivedOnly = mapOf(s.hostId to 4, "another" to 3)
        val coverage = host.list(NetworkQuery()).coverage
        assertEquals(4L, coverage.hostOnly)
        assertEquals(3, coverage.archivedByOther)
        assertEquals(6, host.list(NetworkQuery()).entries.size, "every entry is still listed")
    }
}
