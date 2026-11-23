package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.ProcessRun
import io.yamsergey.dta.protocol.network.RunEnd
import io.yamsergey.dta.protocol.rpc.AppInstallation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 071 US1/US2 (SC-001): the current run by default; the switch includes every run and archived epoch. */
class HostIndexScopeTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun record(app: FakeJournalApp, n: Int, word: String) {
        val text = "{\"note\":\"$word\"}"
        val e = HttpEntry("${app.runId}:$n", app.runId, app.clockMs++, "okhttp3", method = "GET", url = "https://api.example.test/$word/$n", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, text.length.toLong(), completeness = Completeness.COMPLETE, contentType = "application/json", preview = text))
        app.record(e)
    }

    private var FakeJournalApp.clockMs: Long
        get() = clocks.getOrPut(this) { 1_000L }
        set(v) { clocks[this] = v }
    private val clocks = HashMap<FakeJournalApp, Long>()

    /** Two archived epochs (a clear, a new install), then three runs in the live epoch: r3 (2), r4 (3), r5 (2, current). */
    private suspend fun history(): Triple<FakeJournalApp, HostStore, HostIndex> {
        val app = FakeJournalApp()
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity("dev1"), "io.example") { app }
        app.runId = "r1"; record(app, 1, "first"); record(app, 2, "token")
        host.list(NetworkQuery())
        app.reset("j2", "cleared")
        app.runId = "r2"; record(app, 1, "second")
        host.list(NetworkQuery())
        app.installation = AppInstallation("1.5.0", 15, 1, 200, "aa")
        app.reset("j3", "storage-new")
        app.runId = "r3"; record(app, 1, "third"); record(app, 2, "token")
        app.runId = "r4"; record(app, 1, "fourth"); record(app, 2, "fourth"); record(app, 3, "earlier")
        app.runId = "r5"; record(app, 1, "current"); record(app, 2, "now")
        app.processRuns = listOf(
            ProcessRun("r5", 5, startedAtMs = 1_100),
            ProcessRun("r4", 4, startedAtMs = 1_050, endedAtMs = 1_090, ended = RunEnd.PROCESS_ENDED),
            ProcessRun("r3", 3, startedAtMs = 1_000, endedAtMs = 1_040, ended = RunEnd.PROCESS_ENDED),
        )
        host.list(NetworkQuery())
        return Triple(app, store, host)
    }

    @Test
    fun `without the switch only the current run, and what lies outside is said`() = runBlocking<Unit> {
        val (_, _, host) = history()
        val page = host.list(NetworkQuery())
        assertEquals(listOf("r5:2", "r5:1"), page.entries.map { it.id })
        val scope = page.scope!!
        assertFalse(scope.archive)
        assertEquals(2, scope.entries)
        assertEquals("r5", scope.run!!.runId)
        assertEquals(2, scope.run!!.entries)
        assertEquals(1_100, scope.run!!.startedMs)
        assertEquals(5 + 3, scope.outside, "earlier runs (5) and archived epochs (3)")
        assertNull(page.archived)
    }

    @Test
    fun `a word only in earlier history is not found without the switch, and the answer says more exists`() = runBlocking<Unit> {
        val (_, _, host) = history()
        val search = host.search(NetworkQuery(q = "token"))
        assertEquals(emptyList(), search.entries.map { it.id })
        assertEquals(8, search.scope!!.outside)
        assertEquals(emptyList(), host.list(NetworkQuery(q = "token")).entries.map { it.id })
        // run:all widens nothing while the switch is off, and is said.
        val runAll = host.list(NetworkQuery(q = "run:all token"))
        assertEquals(emptyList(), runAll.entries.map { it.id })
        assertTrue(runAll.scope!!.runAllIgnored)
    }

    @Test
    fun `with the switch every run and archived epoch, newest first, archived ones marked`() = runBlocking<Unit> {
        val (_, _, host) = history()
        val page = host.list(NetworkQuery(archive = true))
        assertEquals(listOf("r5:2", "r5:1", "r4:3", "r4:2", "r4:1", "r3:2", "r3:1", "r2:1", "r1:2", "r1:1"), page.entries.map { it.id })
        assertEquals(setOf("r2:1", "r1:2", "r1:1"), page.archived!!.keys)
        val scope = page.scope!!
        assertTrue(scope.archive)
        assertEquals(10, scope.entries)
        assertNull(scope.outside)
        assertEquals(listOf("r5", "r4", "r3"), scope.runs!!.map { it.runId })
        assertEquals(listOf(2L, 3L, 2L), scope.runs!!.map { it.entries })
        assertEquals(RunEnd.PROCESS_ENDED, scope.runs!![1].ended)
        assertEquals(listOf(1L, 2L), scope.epochs!!.map { it.entries }, "newest archived epoch first")
        val search = host.search(NetworkQuery(q = "token", archive = true))
        assertEquals(listOf("r3:2", "r1:2"), search.entries.map { it.id })
        // Inside the switch, run:current narrows again.
        assertEquals(listOf("r5:2", "r5:1"), host.list(NetworkQuery(archive = true, q = "run:current")).entries.map { it.id })
    }

    @Test
    fun `an explicit archived scope answers as before, whatever the switch says`() = runBlocking<Unit> {
        val (_, _, host) = history()
        for (switch in listOf(false, true)) {
            val page = host.list(NetworkQuery(archive = switch, archiveScope = ArchiveScope(all = true)))
            assertEquals(listOf("r2:1", "r1:2", "r1:1"), page.entries.map { it.id })
            assertEquals("all", page.scope!!.explicit)
        }
    }

    @Test
    fun `a new process run moves the default, and an earlier run's late update stays out of it`() = runBlocking<Unit> {
        val (app, _, host) = history()
        app.runId = "r6"; record(app, 1, "newest")
        // r4's entry completes after the restart: its record comes now, its run stays r4.
        app.record((app.records.first { it.id == "r4:3" } as HttpEntry).copy(status = 500))
        val page = host.list(NetworkQuery())
        assertEquals(listOf("r6:1"), page.entries.map { it.id })
        assertEquals("r6", page.scope!!.run!!.runId)
        val decision = host.decide("v", NetworkQuery(), app.records.last(), { null })
        assertFalse(decision.first.match, "an update of an earlier run is not part of the current run")
    }

    /** D-079: what a plain watch asks of the history: its current run and its scope, by the same rule a view decides with. */
    @Test
    fun `a watch's current run and scope are the list's, and an earlier run's late entry is out of it`() = runBlocking<Unit> {
        val (app, _, host) = history()
        assertEquals("r5", host.currentRun)
        val scope = host.scope(NetworkQuery())!!
        assertFalse(scope.archive)
        assertEquals(8, scope.outside)
        assertTrue(host.scope(NetworkQuery(archive = true))!!.archive)
        val late = app.records.first { it.id == "r4:3" }
        assertFalse(LiveScope.includes(NetworkQuery(), late, host.currentRun))
        assertTrue(LiveScope.includes(NetworkQuery(archive = true), late, host.currentRun))
        assertTrue(LiveScope.includes(NetworkQuery(), app.records.first { it.id == "r5:1" }, host.currentRun))
        assertTrue(host.decide("w", NetworkQuery(archive = true), late, { null }).first.match, "with the switch an earlier run's entry is decided")
    }

    @Test
    fun `the walk of the current run does not read earlier runs`() = runBlocking<Unit> {
        val (_, store, host) = history()
        store.readPage = 1
        store.rowsRead = 0
        val reads = host.list(NetworkQuery(q = "nothing-here")).let { store.rowsRead }
        assertTrue(reads <= 3, "read $reads rows for a run of 2")
    }
}
