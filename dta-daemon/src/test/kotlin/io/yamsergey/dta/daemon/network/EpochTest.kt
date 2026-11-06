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
import kotlin.test.assertTrue

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

    // ---- feature 046: the journal went back in time, with the same id ------------------------

    private fun newRun(app: FakeJournalApp, from: Int, count: Int) {
        app.runId = "r9-$from"
        repeat(count) { record(app, from + it) }
    }

    private fun journalReads(app: FakeJournalApp) = app.requests.count { it == "network_journal" }

    @Test
    fun `a journal gone back behind the position starts over, and the earlier history is archived`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        app.goBack(2)
        newRun(app, 50, 1)
        val page = host.list(NetworkQuery())
        assertEquals(setOf("r1-1:1", "r1-1:2", "r9-50:50"), page.entries.map { it.id }.toSet(), "the new request is there")
        val epoch = archived(s).single()
        assertEquals(EpochCloseReason.ROLLED_BACK, epoch.reason)
        assertEquals(5, s.entries(epoch.id).size, "nothing read before is removed")
        assertNotNull(host.entry("r1-1:5").archived, "what only the archive has can still be read")
        assertEquals("r9-50:50", host.entry("r9-50:50").entry.id)
    }

    @Test
    fun `a journal that went back and already passed the position is noticed too, with what was written in between`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        app.goBack(2)
        newRun(app, 50, 6)
        assertEquals(8, host.list(NetworkQuery()).total, "two from before and all six since")
        assertEquals(EpochCloseReason.ROLLED_BACK, archived(s).single().reason)
    }

    @Test
    fun `the record the daemon read last being gone by eviction is not a rollback`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        newRun(app, 50, 2)
        (1..5).forEach { app.evicted += "r1-1:$it" }
        assertTrue(host.list(NetworkQuery()).entries.any { it.id == "r9-50:51" })
        assertEquals(emptyList(), archived(s))
    }

    @Test
    fun `a last line cut when it was read is not a rollback, and is read once whole`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        app.cutLines += 4
        val s = store()
        val host = index(app, s)
        assertEquals(4, host.list(NetworkQuery()).total)
        app.cutLines.clear()
        assertEquals(5, host.list(NetworkQuery()).total, "the line is whole now")
        assertEquals(emptyList(), archived(s))
    }

    @Test
    fun `ordinary life is not a rollback`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(7) { record(app, it + 1) }
        val s = store()
        val host = index(app, s).apply { pageSize = 2 }
        host.list(NetworkQuery()) // several pages
        assertEquals(7, host.list(NetworkQuery()).total) // nothing new
        newRun(app, 50, 1) // the app restarted
        assertEquals(8, host.list(NetworkQuery()).total)
        val few = index(app, s).apply { pageSize = 1; catchUpBudgetPages = 1 } // the daemon restarted, and reads little at a time
        newRun(app, 60, 3)
        repeat(4) { few.list(NetworkQuery()) }
        assertEquals(11, few.list(NetworkQuery()).total)
        assertEquals(emptyList(), archived(s))
    }

    @Test
    fun `the check adds no request when nothing went back`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val host = index(app)
        host.list(NetworkQuery())
        var before = journalReads(app)
        host.list(NetworkQuery())
        assertEquals(before + 1, journalReads(app), "nothing new")
        newRun(app, 50, 1)
        before = journalReads(app)
        host.list(NetworkQuery())
        assertEquals(before + 1, journalReads(app), "one new record")
    }

    @Test
    fun `a position from an earlier version keeps working, and one beyond the journal is a rollback`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        s.setCursor("dev1", "io.example", "j1", "0:5", null)
        newRun(app, 50, 1)
        assertEquals(6, host.list(NetworkQuery()).total)
        assertEquals(emptyList(), archived(s), "inside the journal")
        s.setCursor("dev1", "io.example", "j1", "0:99", null)
        host.list(NetworkQuery())
        assertEquals(EpochCloseReason.ROLLED_BACK, archived(s).single().reason, "beyond it")
    }

    @Test
    fun `a position in another form is read as before`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(5) { record(app, it + 1) }
        val s = store()
        val host = index(app, s)
        host.list(NetworkQuery())
        s.setCursor("dev1", "io.example", "j1", "5", "r1-1:5")
        newRun(app, 50, 1)
        assertEquals(6, host.list(NetworkQuery()).total)
        assertEquals(emptyList(), archived(s))
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
