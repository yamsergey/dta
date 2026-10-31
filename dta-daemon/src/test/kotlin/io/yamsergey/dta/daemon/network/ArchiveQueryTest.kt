package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeleteTarget
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.AppInstallation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 011 T019/T020 (US4): look back — archived epochs answer the same queries, marked. */
class ArchiveQueryTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun record(app: FakeJournalApp, n: Int, url: String, body: String? = null) {
        val e = HttpEntry("${app.runId}:$n", app.runId, n.toLong(), "okhttp3", method = "GET", url = url, state = HttpState.COMPLETE, status = 200,
            responseBody = body?.let { BodyInfo(BodyPart.RESPONSE, it.length.toLong(), completeness = Completeness.COMPLETE, contentType = "text/plain") })
        app.record(e)
        body?.let { app.bodies[e.id to BodyPart.RESPONSE] = it.toByteArray() }
    }

    /** Version 1.4.0 with two epochs (the first cleared), then 1.5.0 live. */
    private suspend fun history(): Triple<FakeJournalApp, HostStore, HostIndex> {
        val app = FakeJournalApp()
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity("dev1"), "io.example") { app }
        app.runId = "r1-1"
        record(app, 1, "https://api/cart", body = "old cart")
        record(app, 2, "https://api/user")
        host.list(NetworkQuery(filter = NetworkFilter(bodyContains = "cart")))
        app.reset("j2", "cleared")
        app.runId = "r2-1"
        record(app, 1, "https://api/cart")
        host.list(NetworkQuery())
        app.installation = AppInstallation("1.5.0", 15, 1, 200, "aa")
        app.reset("j3", "storage-new")
        app.runId = "r3-1"
        record(app, 1, "https://api/checkout")
        host.list(NetworkQuery())
        return Triple(app, store, host)
    }

    @Test
    fun `without a scope only the live history, and the archive is counted`() = runBlocking<Unit> {
        val (_, _, host) = history()
        val live = host.list(NetworkQuery())
        assertEquals(listOf("r3-1:1"), live.entries.map { it.id })
        assertEquals(2, live.coverage.archivedEpochs)
        assertNull(live.archived)
    }

    @Test
    fun `each scope gives its epochs, every entry marked`() = runBlocking<Unit> {
        val (_, store, host) = history()
        val all = host.list(NetworkQuery(archive = ArchiveScope(all = true), filter = NetworkFilter(url = "cart")))
        assertEquals(setOf("r1-1:1", "r2-1:1"), all.entries.map { it.id }.toSet())
        val marks = assertNotNull(all.archived)
        assertEquals(EpochCloseReason.CLEARED, marks.getValue("r1-1:1").reason)
        assertEquals(EpochCloseReason.NEW_BUILD, marks.getValue("r2-1:1").reason)
        assertEquals("1.4.0", marks.getValue("r1-1:1").version)

        val v14 = host.list(NetworkQuery(archive = ArchiveScope(version = "1.4.0")))
        assertEquals(3, v14.total)
        val epoch = store.epochs("dev1", "io.example", ArchiveScope(all = true)).first().id
        assertEquals(2, host.list(NetworkQuery(archive = ArchiveScope(epoch = epoch))).total)
    }

    @Test
    fun `archived entries and bodies are served from the host, with the app gone`() = runBlocking<Unit> {
        val (app, _, host) = history()
        app.records.clear(); app.bodies.clear()
        val detail = host.entry("r1-1:1")
        assertEquals("https://api/cart", (detail.entry as HttpEntry).url)
        assertEquals(EpochCloseReason.CLEARED, detail.archived!!.reason)
        val body = assertNotNull(host.storedBody("r1-1:1", BodyPart.RESPONSE))
        assertContentEquals("old cart".toByteArray(), body.second)
    }

    @Test
    fun `a body search covers the archive too`() = runBlocking<Unit> {
        val (_, _, host) = history()
        val found = host.list(NetworkQuery(archive = ArchiveScope(all = true), filter = NetworkFilter(bodyContains = "old cart")))
        assertEquals(listOf("r1-1:1"), found.entries.map { it.id })
    }

    @Test
    fun `delete previews, then removes, and the live epoch is refused`() = runBlocking<Unit> {
        val (_, store, host) = history()
        val first = store.epochs("dev1", "io.example", ArchiveScope(all = true)).first().id
        val preview = store.delete(DeleteTarget.Epoch(first), dryRun = true)
        assertEquals(2, preview.entries)
        assertEquals(1, preview.bodiesFreed)
        assertEquals(2, store.epochs("dev1", "io.example", ArchiveScope(all = true)).size, "a preview deletes nothing")
        store.delete(DeleteTarget.Epoch(first))
        assertEquals(1, host.list(NetworkQuery(archive = ArchiveScope(all = true))).total)
        assertEquals(1, store.delete(DeleteTarget.Package("io.example")).epochs, "an app's archive, on every device, its live epoch kept")
        assertTrue(host.list(NetworkQuery()).total == 1)
        assertFailsWith<io.yamsergey.dta.daemon.store.LiveEpochException> { store.delete(DeleteTarget.Epoch(store.liveEpoch("dev1", "io.example")!!.id)) }
    }
}
