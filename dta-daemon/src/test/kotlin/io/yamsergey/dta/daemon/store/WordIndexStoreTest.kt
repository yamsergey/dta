package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.LinkDelivery
import io.yamsergey.dta.protocol.network.LinkEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 070 A1 (research R5): every write that changes what an entry can be found by leaves it
 * pending for the word index, in the same transaction; a delete takes its words with it.
 */
class WordIndexStoreTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store() = HostStore(dir).also { stores += it }
    private val device = DeviceIdentity("dev1", "emulator-5554")

    private fun <T> sql(query: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "dta.db").path}").use { c ->
            c.prepareStatement(query).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
            }
        }

    private fun rowid(epoch: String, id: String) = sql("SELECT rowid FROM entry WHERE epoch_id=? AND entry_id=?", epoch, id) { it.getLong(1) }.single()
    private fun pending() = sql("SELECT rowid, mark FROM word_pending ORDER BY rowid") { it.getLong(1) to it.getInt(2) }

    private fun call(id: String, binary: Boolean = false) = HttpEntry(id, "r1-1", 1, "okhttp3", method = "GET", url = "https://api.example.test/$id", state = HttpState.COMPLETE, status = 200,
        responseBody = BodyInfo(BodyPart.RESPONSE, 40, contentType = if (binary) "application/x-protobuf" else "application/json", completeness = Completeness.COMPLETE, binary = binary))

    @Test
    fun `the bundled SQLite has FTS5 trigram with contentless delete`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE VIRTUAL TABLE w USING fts5(t, content='', contentless_delete=1, detail=none, tokenize='trigram case_sensitive 1')")
                st.execute("INSERT INTO w(rowid, t) VALUES (5, 'lineitem123'), (7, 'other')")
                st.execute("DELETE FROM w WHERE rowid=7")
                val rows = st.executeQuery("SELECT rowid FROM w WHERE w MATCH '\"ite\" AND \"m12\"' AND rowid < 10 ORDER BY rowid DESC").use { r -> buildList { while (r.next()) add(r.getLong(1)) } }
                assertEquals(listOf(5L), rows)
            }
        }
    }

    @Test
    fun `new, changed and body-copied entries are pending, each change marked`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", InstallationInfo(), "j1", 0)
        s.putEntries(epoch.id, listOf(call("a"), call("b", binary = true)))
        val a = rowid(epoch.id, "a")
        val b = rowid(epoch.id, "b")
        assertEquals(listOf(a to 0, b to 0), pending())
        assertEquals(listOf(0, 1), sql("SELECT derived FROM entry ORDER BY rowid") { it.getInt(1) }, "a binary body makes its words depend on the app's schemas")
        s.putEntries(epoch.id, listOf(call("a").copy(status = 404)))
        assertEquals(listOf(a to 1, b to 0), pending())
        s.putBody(epoch.id, "b", BodyPart.RESPONSE, "bytes".byteInputStream())
        assertEquals(listOf(a to 1, b to 1), pending())
    }

    @Test
    fun `the indexer's write takes a pending row only if nothing changed since it was read`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", InstallationInfo(), "j1", 0)
        s.putEntries(epoch.id, listOf(call("a"), call("b")))
        val read = s.pendingWords(listOf(epoch.id), 10)
        assertEquals(listOf("b", "a"), read.map { it.entry.id }, "newest first")
        s.putEntries(epoch.id, listOf(call("a").copy(status = 500)))
        s.putWords(read.map { WordWrite(it.rowid, it.mark, "apiexampletest${it.entry.id}", cut = it.entry.id == "b") })
        assertEquals(listOf(rowid(epoch.id, "a")), pending().map { it.first }, "a changed after it was read: still pending")
        assertEquals(listOf(rowid(epoch.id, "b")), sql("SELECT rowid FROM word_partial") { it.getLong(1) })
        assertEquals(listOf(rowid(epoch.id, "b")), sql("SELECT rowid FROM entry_words WHERE entry_words MATCH '\"stb\"'") { it.getLong(1) })
        assertEquals(1, s.pendingWordCount(listOf(epoch.id)))
    }

    @Test
    fun `derived entries are made pending again when the app's schemas change`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", InstallationInfo(), "j1", 0)
        s.putEntries(epoch.id, listOf(call("a"), call("b", binary = true)))
        s.putWords(s.pendingWords(listOf(epoch.id), 10).map { WordWrite(it.rowid, it.mark, "x${it.entry.id}", cut = false) })
        assertEquals(emptyList(), pending())
        assertEquals(null, s.wordFingerprint(device.key, "io.example"))
        s.markDerivedPending(listOf(epoch.id))
        s.setWordFingerprint(device.key, "io.example", "f2")
        assertEquals(listOf(rowid(epoch.id, "b")), pending().map { it.first })
        assertEquals("f2", s.wordFingerprint(device.key, "io.example"))
    }

    @Test
    fun `deleting an epoch removes its words`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", InstallationInfo(), "j1", 0)
        s.putEntries(epoch.id, listOf(call("a"), call("b"), LinkEntry("c", "r1-1", 3, uri = "app://x", activity = "Main", delivery = LinkDelivery.COLD_START)))
        val read = s.pendingWords(listOf(epoch.id), 10)
        s.putWords(read.filter { it.entry.id != "c" }.map { WordWrite(it.rowid, it.mark, "word${it.entry.id}", cut = it.entry.id == "a") })
        s.closeEpoch(epoch.id, 9, EpochCloseReason.CLEARED, 0)
        s.delete(DeleteTarget.Epoch(epoch.id))
        assertEquals(emptyList(), pending())
        assertEquals(emptyList(), sql("SELECT rowid FROM word_partial") { it.getLong(1) })
        assertEquals(emptyList(), sql("SELECT rowid FROM entry_words WHERE entry_words MATCH '\"wor\"'") { it.getLong(1) })
    }

    @Test
    fun `an archive of format 2 is started over`() {
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "dta.db").path}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE entry(x)"); it.execute("PRAGMA user_version=2") }
        }
        val s = store()
        assertTrue(s.startedOver?.contains("word index") == true, "${s.startedOver}")
        assertEquals(listOf(3), sql("PRAGMA user_version") { it.getInt(1) })
    }
}
