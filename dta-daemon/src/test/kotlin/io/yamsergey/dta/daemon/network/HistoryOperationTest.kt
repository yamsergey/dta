package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.store.DeleteTarget
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 066 T024: the per-app picture of the history, and its settings (contracts/operations.md). */
class HistoryOperationTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()
    private var now = 100_000L

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store() = HostStore(dir).also { stores += it }

    private fun http(app: FakeJournalApp, n: Int, body: String? = null) =
        HttpEntry("${app.runId}:$n", app.runId, now + n, "okhttp3", method = "GET", url = "https://api/x/$n", state = HttpState.COMPLETE, status = 200,
            responseBody = body?.let { BodyInfo(BodyPart.RESPONSE, it.length.toLong(), completeness = Completeness.COMPLETE) })
            .also { e -> app.record(e); body?.let { app.bodies[e.id to BodyPart.RESPONSE] = it.toByteArray() } }

    @Test
    fun `the device's part and this computer's, per app`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(6) { http(app, it + 1, body = if (it == 0) "body" else null) }
        val host = HostIndex(store(), DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { app }
        host.list(NetworkQuery())
        app.entriesOverride = 4
        val summary = host.history()
        val device = assertNotNull(summary.device)
        assertEquals(4, device.entries)
        assertEquals(10_000L, device.bytes)
        assertEquals(256L shl 20, device.keepBytes)
        assertEquals(3, device.archivedEntries, "one entry still waits for its body")
        assertEquals(now + 6, device.latestMs)
        assertEquals(6L, summary.archive.entries, "the live epoch is on this computer")
        assertEquals(1, summary.archive.installations.size)
        assertEquals(2L, summary.hostOnly)
        assertNull(summary.deviceUnknown)
    }

    @Test
    fun `when the app cannot be reached the device's part is unknown, never zero`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        http(app, 1)
        val s = store()
        HostIndex(s, DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { app }.list(NetworkQuery())
        val gone = HostIndex(s, DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { error("io.example is not running") }
        val summary = gone.history()
        assertNull(summary.device)
        assertTrue(summary.deviceUnknown!!.contains("not running"), summary.deviceUnknown)
        assertEquals(1L, summary.archive.entries)
    }

    @Test
    fun `an older library is said, not shown as empty`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { reportsSize = false }
        http(app, 1)
        val summary = HostIndex(store(), DeviceIdentity("dev1", "emulator-5554"), "io.example", clock = { now }) { app }.history()
        assertNull(summary.device)
        assertTrue(summary.deviceUnknown!!.contains("update it"))
    }

    @Test
    fun `settings go to the device and come back as in force`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        val service = NetworkService(connection = { app as AppConnection })
        val set = service.setHistory(keepBytes = 64L shl 20, warnBytes = null)
        assertEquals(64L shl 20, set.keepBytes)
        assertEquals(64L shl 20, app.keep)
        assertEquals(512L shl 20, set.warnBytes)
    }

    @Test
    fun `deleting before a date can be one app's only`() {
        val s = store()
        val device = DeviceIdentity("dev1", "emulator-5554")
        val install = io.yamsergey.dta.daemon.store.InstallationInfo(versionName = "1.0", versionCode = 1, lastUpdateMs = 100, signingSha256 = "aa")
        listOf("io.example", "io.other").forEach { pkg ->
            val epoch = s.openEpoch(device, pkg, install, "j-$pkg", 1_000)
            s.closeEpoch(epoch.id, 2_000, io.yamsergey.dta.protocol.network.EpochCloseReason.CLEARED, 0)
        }
        val preview = s.delete(DeleteTarget.ClosedBefore(5_000, "io.example"), dryRun = true)
        assertEquals(1, preview.epochs)
        assertEquals(2, s.delete(DeleteTarget.ClosedBefore(5_000), dryRun = true).epochs)
    }
}
