package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.adb.AppNotRunningException
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.PatternStop
import io.yamsergey.dta.protocol.network.ProcessRun
import io.yamsergey.dta.protocol.network.RunEnd
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * D-060 (071 edge "The app is not running"): with the app stopped, the current session is the last process run this
 * computer received, answered from its copy, and the answer says that run ended - list, search and a streamed search.
 */
class HostIndexStoppedTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private var running = true
    private val app = FakeJournalApp()
    private var clock = 1_000L

    private fun record(n: Int, word: String) {
        val text = "{\"note\":\"$word\"}"
        app.record(HttpEntry("${app.runId}:$n", app.runId, clock++, "okhttp3", method = "GET", url = "https://api.example.test/$word/$n", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, text.length.toLong(), completeness = Completeness.COMPLETE, contentType = "application/json", preview = text)))
    }

    private var now = 7_000L
    private fun host(store: HostStore) = HostIndex(store, DeviceIdentity("dev1"), "io.example", clock = { now }) {
        if (!running) throw AppNotRunningException("io.example is not running on dev1 — launch it first")
        app
    }

    /** Run r1 (1 entry, ended), then r2 (2 entries, the current one) copied here; then the app is force-stopped. */
    private suspend fun stopped(): Pair<HostStore, HostIndex> {
        val store = HostStore(dir).also { stores += it }
        val host = host(store)
        app.runId = "r1"; record(1, "old")
        app.runId = "r2"; record(1, "token"); record(2, "now")
        app.processRuns = listOf(ProcessRun("r2", 2, startedAtMs = 1_100), ProcessRun("r1", 1, startedAtMs = 1_000, endedAtMs = 1_050, ended = RunEnd.PROCESS_ENDED))
        host.list(NetworkQuery())
        running = false
        return store to host
    }

    @Test
    fun `a list with the app stopped answers the last run from the copy and says it ended`() = runBlocking<Unit> {
        val (_, host) = stopped()
        host.appEnded(5_000)
        val page = host.list(NetworkQuery())
        assertEquals(listOf("r2:2", "r2:1"), page.entries.map { it.id })
        val run = page.scope!!.run!!
        assertEquals("r2", run.runId)
        assertEquals(2, run.entries)
        assertEquals(RunEnd.PROCESS_ENDED, run.ended)
        assertEquals(5_000L, run.endedMs)
        assertEquals(2, page.scope!!.entries)
    }

    /**
     * D-060 (harness NET-QRY-085): an answer can come before the connection manager says the process ended; the first answer
     * that finds the app gone records the end then, and the later "ended" event does not move it.
     */
    @Test
    fun `the first answer that finds the app gone says when, before the ended event`() = runBlocking<Unit> {
        val (_, host) = stopped()
        val first = host.list(NetworkQuery()).scope!!.run!!
        assertEquals(RunEnd.PROCESS_ENDED, first.ended)
        assertEquals(7_000L, first.endedMs, "the time this computer found the app gone")
        now = 9_000L
        host.appEnded(8_500)
        assertEquals(7_000L, host.list(NetworkQuery()).scope!!.run!!.endedMs, "kept: the earliest time the end was seen")
    }

    @Test
    fun `a search and a streamed search with the app stopped read the last run`() = runBlocking<Unit> {
        val (_, host) = stopped()
        host.appEnded(5_000)
        val found = host.search(NetworkQuery(q = "token"))
        assertEquals(listOf("r2:1"), found.entries.map { it.id })
        assertEquals(RunEnd.PROCESS_ENDED, found.scope!!.run!!.ended)
        val pass = host.find(NetworkQuery(q = "token"), null)
        assertEquals("r2", pass.scope.run!!.runId)
        assertEquals(5_000L, pass.scope.run!!.endedMs)
        val items = mutableListOf<String>()
        pass.walk(PatternStop.NONE) { items += it.entry.id }
        assertEquals(listOf("r2:1"), items)
    }

    @Test
    fun `a daemon that never saw the app run answers its last run from the copy, ended at an unknown time`() = runBlocking<Unit> {
        val (store, _) = stopped()
        val fresh = host(store)
        val page = fresh.list(NetworkQuery())
        assertEquals(listOf("r2:2", "r2:1"), page.entries.map { it.id })
        assertEquals("r2", page.scope!!.run!!.runId)
        assertEquals(RunEnd.PROCESS_ENDED, page.scope!!.run!!.ended)
    }

    @Test
    fun `with nothing copied the stopped app is still said not running`() = runBlocking<Unit> {
        val store = HostStore(dir).also { stores += it }
        running = false
        assertFailsWith<AppNotRunningException> { host(store).list(NetworkQuery()) }
    }
}
