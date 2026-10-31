package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.AppInstallation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 011 T015 (US3): when the device forgets, the host archives instead of deleting. */
class EpochTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store() = HostStore(dir).also { stores += it }

    private fun index(app: FakeJournalApp, s: HostStore = store()) = HostIndex(s, DeviceIdentity("dev1"), "io.example") { app }

    private fun record(app: FakeJournalApp, n: Int) =
        app.record(HttpEntry("${app.runId}:$n", app.runId, n.toLong(), "okhttp3", method = "GET", url = "https://api/$n", state = HttpState.COMPLETE, status = 200))

    private fun archived(s: HostStore) = s.listing().devices.single().apps.single().installations.flatMap { it.epochs }.filter { !it.live }

    @Test
    fun `a clear on the device archives the history - with the count that never reached the host`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        repeat(3) { record(app, it + 6) } // recorded, never read by the host
        host.cleared(clearedEntries = 8)
        app.reset("j2", "cleared")
        assertEquals(0, host.list(NetworkQuery()).total, "the live history starts empty")
        val epoch = archived(s).single()
        assertEquals(EpochCloseReason.CLEARED, epoch.reason)
        assertEquals(5, epoch.entries, "what the host had is kept")
        assertEquals(3, epoch.neverReceived)
        assertEquals(5, s.entries(epoch.id).size)
    }

    @Test
    fun `new storage is a new build, a reinstall or a data wipe, by the installation`() = runBlocking<Unit> {
        listOf(
            AppInstallation("1.5.0", 15, 1, 200, "aa") to EpochCloseReason.NEW_BUILD,
            AppInstallation("1.4.0", 14, 1, 300, "aa") to EpochCloseReason.REINSTALLED,
            AppInstallation("1.4.0", 14, 1, 100, "aa") to EpochCloseReason.DATA_WIPED,
        ).forEachIndexed { i, (after, reason) ->
            val sub = File(dir, "case$i")
            val s = HostStore(sub).also { stores += it }
            val app = FakeJournalApp()
            record(app, 1)
            val host = index(app, s)
            host.list(NetworkQuery())
            app.installation = after
            app.reset("j-new", "storage-new")
            host.list(NetworkQuery())
            assertEquals(reason, archived(s).single().reason, "after $after")
        }
    }

    @Test
    fun `a daemon restart keeps the archive and the live history`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(4) { record(app, it + 1) }
        val s = store()
        index(app, s).list(NetworkQuery())
        app.reset("j2", "cleared")
        record(app, 10)
        index(app, s).list(NetworkQuery())
        s.close(); stores.clear()
        val again = store()
        assertEquals(1, index(app, again).list(NetworkQuery()).total)
        assertNotNull(again.epochs("dev1", "io.example", ArchiveScope(all = true)).singleOrNull())
        assertEquals(1, index(app, again).list(NetworkQuery()).coverage.archivedEpochs, "the archive is stated beside the live list")
    }
}
