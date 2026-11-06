package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 011 T004: the host store — epochs, entries, bodies by content, delete, privacy. */
class HostStoreTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store(bodies: BodyStore? = null) = (if (bodies == null) HostStore(dir) else HostStore(dir, bodies)).also { stores += it }

    private val device = DeviceIdentity(key = "dev1", serial = "emulator-5554", model = "Pixel 8", release = "16")
    private val v14 = InstallationInfo(versionName = "1.4.0", versionCode = 14, lastUpdateMs = 100, signingSha256 = "aa")
    private val v15 = InstallationInfo(versionName = "1.5.0", versionCode = 15, lastUpdateMs = 200, signingSha256 = "aa")

    private fun http(id: String, url: String = "https://api/x", time: Long = 1) =
        HttpEntry(id = id, runId = "r1-1", timeMs = time, library = "okhttp3", method = "GET", url = url, state = HttpState.COMPLETE, status = 200)

    @Test
    fun `an epoch holds entries in order, updated in place`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", v14, journalId = "j1", openedMs = 1)
        s.putEntries(epoch.id, listOf(http("r1-1:1"), http("r1-1:2")))
        s.putEntries(epoch.id, listOf(http("r1-1:1").copy(status = 404)))
        val entries = s.entries(epoch.id)
        assertEquals(listOf("r1-1:1", "r1-1:2"), entries.map { it.id })
        assertEquals(404, (entries.first() as HttpEntry).status)
        assertEquals(epoch, s.liveEpoch("dev1", "io.example"))
    }

    @Test
    fun `closing keeps everything and opens nothing by itself`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", v14, "j1", 1)
        s.putEntries(epoch.id, listOf(http("r1-1:1")))
        s.closeEpoch(epoch.id, closedMs = 50, reason = EpochCloseReason.CLEARED, neverReceived = 3)
        assertNull(s.liveEpoch("dev1", "io.example"))
        assertEquals(1, s.entries(epoch.id).size)
        val listed = s.listing().devices.single().apps.single().installations.single().epochs.single()
        assertEquals(EpochCloseReason.CLEARED, listed.reason)
        assertEquals(3, listed.neverReceived)
        assertEquals(1, listed.entries)
    }

    @Test
    fun `a store from before the last record was remembered keeps its positions`() {
        val s = store()
        s.setCursor("dev1", "io.example", "j1", "3:40", "r1-1:9")
        assertEquals(Cursor("j1", "3:40", "r1-1:9"), s.cursor("dev1", "io.example"))
        s.close(); stores.clear()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${java.io.File(dir, "dta.db").path}").use { c ->
            c.createStatement().use { it.execute("ALTER TABLE cursor DROP COLUMN last_id") }
        }
        assertEquals(Cursor("j1", "3:40", null), store().cursor("dev1", "io.example"))
    }

    @Test
    fun `the store survives a restart`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", v14, "j1", 1)
        s.putEntries(epoch.id, listOf(http("r1-1:1")))
        s.setCursor("dev1", "io.example", "j1", "seg1:40")
        s.close(); stores.clear()
        val again = store()
        assertEquals(epoch.id, again.liveEpoch("dev1", "io.example")!!.id)
        assertEquals(Cursor("j1", "seg1:40"), again.cursor("dev1", "io.example"))
        assertEquals(1, again.entries(epoch.id).size)
    }

    @Test
    fun `a body is stored once per content and served back`() {
        val s = store()
        val a = s.openEpoch(device, "io.example", v14, "j1", 1)
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        s.putBody(a.id, "r1-1:1", BodyPart.RESPONSE, bytes.inputStream())
        s.putBody(a.id, "r1-1:2", BodyPart.RESPONSE, bytes.inputStream())
        val ref = assertNotNull(s.bodyRef(a.id, "r1-1:1", BodyPart.RESPONSE))
        assertEquals(ref.sha256, s.bodyRef(a.id, "r1-1:2", BodyPart.RESPONSE)!!.sha256)
        assertEquals(1, File(dir, "bodies").walkTopDown().count { it.isFile }, "one file for both")
        assertContentEquals(bytes, s.openBody(ref.sha256!!)!!.readBytes())
    }

    @Test
    fun `a body that could not be copied is marked, with the reason`() {
        val s = store()
        val a = s.openEpoch(device, "io.example", v14, "j1", 1)
        s.markNotArchived(a.id, "r1-1:1", BodyPart.RESPONSE, "the device no longer had it")
        assertEquals("the device no longer had it", s.bodyRef(a.id, "r1-1:1", BodyPart.RESPONSE)!!.notArchived)
    }

    @Test
    fun `scopes select epochs - by id, version, time, or all closed ones`() {
        val s = store()
        val a = s.openEpoch(device, "io.example", v14, "j1", 10).also { s.closeEpoch(it.id, 20, EpochCloseReason.CLEARED, 0) }
        val b = s.openEpoch(device, "io.example", v14, "j2", 30).also { s.closeEpoch(it.id, 40, EpochCloseReason.NEW_BUILD, 0) }
        val c = s.openEpoch(device, "io.example", v15, "j3", 50).also { s.closeEpoch(it.id, 60, EpochCloseReason.CLEARED, 0) }
        s.openEpoch(device, "io.example", v15, "j4", 70)
        fun ids(scope: ArchiveScope) = s.epochs("dev1", "io.example", scope).map { it.id }.toSet()
        assertEquals(setOf(b.id), ids(ArchiveScope(epoch = b.id)))
        assertEquals(setOf(a.id, b.id), ids(ArchiveScope(version = "1.4.0")))
        assertEquals(setOf(b.id, c.id), ids(ArchiveScope(fromMs = 35, toMs = 55)))
        assertEquals(setOf(a.id, b.id, c.id), ids(ArchiveScope(all = true)), "the live epoch is never archived")
        assertEquals(3, s.archivedCount("dev1", "io.example"))
    }

    @Test
    fun `delete removes what it names and keeps shared bodies`() {
        val s = store()
        val shared = ByteArray(1000) { 7 }
        val a = s.openEpoch(device, "io.example", v14, "j1", 10)
        s.putEntries(a.id, listOf(http("r1-1:1")))
        s.putBody(a.id, "r1-1:1", BodyPart.RESPONSE, shared.inputStream())
        s.putBody(a.id, "r1-1:1", BodyPart.REQUEST, ByteArray(10) { 1 }.inputStream())
        s.closeEpoch(a.id, 20, EpochCloseReason.CLEARED, 0)
        val b = s.openEpoch(device, "io.example", v14, "j2", 30)
        s.putEntries(b.id, listOf(http("r2-1:1")))
        s.putBody(b.id, "r2-1:1", BodyPart.RESPONSE, shared.inputStream())
        s.closeEpoch(b.id, 40, EpochCloseReason.CLEARED, 0)

        val deleted = s.delete(DeleteTarget.Epoch(a.id))
        assertEquals(1, deleted.epochs)
        assertEquals(1, deleted.entries)
        assertEquals(1, deleted.bodiesFreed, "the request body only; the shared response body stays")
        assertContentEquals(shared, s.openBody(s.bodyRef(b.id, "r2-1:1", BodyPart.RESPONSE)!!.sha256!!)!!.readBytes())
        assertEquals(listOf(b.id), s.epochs("dev1", "io.example", ArchiveScope(all = true)).map { it.id })
    }

    @Test
    fun `delete by app, by installation or before a date`() {
        val s = store()
        s.openEpoch(device, "io.example", v14, "j1", 10).also { s.closeEpoch(it.id, 20, EpochCloseReason.CLEARED, 0) }
        val late = s.openEpoch(device, "io.example", v15, "j2", 30).also { s.closeEpoch(it.id, 40, EpochCloseReason.CLEARED, 0) }
        assertEquals(1, s.delete(DeleteTarget.ClosedBefore(25)).epochs)
        assertEquals(listOf(late.id), s.epochs("dev1", "io.example", ArchiveScope(all = true)).map { it.id })
        assertEquals(1, s.delete(DeleteTarget.App("dev1", "io.example")).epochs)
    }

    @Test
    fun `the live epoch cannot be deleted - it is cleared on the device`() {
        val s = store()
        val live = s.openEpoch(device, "io.example", v14, "j1", 10)
        val e = assertFailsWith<LiveEpochException> { s.delete(DeleteTarget.Epoch(live.id)) }
        assertTrue("Clear" in e.message!!, e.message)
        assertEquals(0, s.delete(DeleteTarget.App("dev1", "io.example")).epochs, "an app's delete skips its live epoch")
        assertNotNull(s.liveEpoch("dev1", "io.example"))
    }

    @Test
    fun `the store is readable by its owner only`() {
        store().openEpoch(device, "io.example", v14, "j1", 1)
        val perms = Files.getPosixFilePermissions(dir.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), perms)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(File(dir, "dta.db").toPath()))
    }

    @Test
    fun `when bodies cannot be written, archiving stops and says why`() {
        val failing = object : BodyStore {
            override fun put(input: java.io.InputStream): BodyStore.Stored = throw IOException("No space left on device")
            override fun open(sha256: String) = null
            override fun delete(sha256: String) = 0L
        }
        val s = store(failing)
        val a = s.openEpoch(device, "io.example", v14, "j1", 1)
        assertNull(s.archivingStopped)
        assertFailsWith<ArchivingStoppedException> { s.putBody(a.id, "r1-1:1", BodyPart.RESPONSE, ByteArray(5).inputStream()) }
        assertTrue("No space left" in s.archivingStopped!!, s.archivingStopped)
    }
}
