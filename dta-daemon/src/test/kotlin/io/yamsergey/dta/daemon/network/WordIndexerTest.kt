package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.store.InstallationInfo
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.IndexText
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 070 A3 (research R5, R8): the indexer writes each pending entry's [IndexText], a batch at a time. */
class WordIndexerTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private val device = DeviceIdentity("dev1", "emulator-5554")

    private fun <T> sql(query: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "dta.db").path}").use { c ->
            c.prepareStatement(query).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
            }
        }

    private fun matching(key: String) = sql("SELECT rowid FROM entry_words WHERE entry_words MATCH ?", key.windowed(3).joinToString(" AND ") { "\"$it\"" }) { it.getLong(1) }

    private fun call(id: String, size: Long, binary: Boolean = false, preview: String? = null) = HttpEntry(id, "r1-1", 1, "okhttp3", method = "GET", url = "https://api.example.test/$id",
        state = HttpState.COMPLETE, status = 200,
        responseBody = BodyInfo(BodyPart.RESPONSE, size, contentType = if (binary) "application/x-protobuf" else "application/json", binary = binary, completeness = Completeness.COMPLETE, preview = preview))

    private class Setup(val store: HostStore, val epoch: String, val indexer: WordIndexer, var schemas: String)

    private fun setup(decoded: String? = null, clock: () -> Long = System::nanoTime): Setup {
        val store = HostStore(dir).also { stores += it }
        val epoch = store.openEpoch(device, "io.example", InstallationInfo(), "j1", 0).id
        lateinit var s: Setup
        val bytes = { e: io.yamsergey.dta.protocol.network.NetworkEntry, p: BodyPart ->
            store.bodyRef(epoch, e.id, p)?.sha256?.let { sha -> store.openBody(sha)?.use { NetworkFilterEval.BodyBytes(it.readBytes(), true) } }
        }
        val indexer = WordIndexer(store, device.key, "io.example", { listOf(epoch) }, { s.schemas },
            { _, e -> IndexText.of(e, bytes, decoded?.let { d -> { en, _ -> if (NetworkHistoryView.bodyOf(en, BodyPart.RESPONSE)?.binary == true) "{\"name\":\"$d-${s.schemas}\"}" else null } }) }, clock)
        s = Setup(store, epoch, indexer, "v1")
        return s
    }

    @Test
    fun `pending entries get their words, newest first, and a body copied later is indexed again`() {
        val s = setup()
        s.store.putEntries(s.epoch, listOf(call("first", 900, preview = "{\"name\":\"item-"), call("second", 0)))
        assertEquals(2, s.indexer.pending())
        assertEquals(2, s.indexer.step())
        assertEquals(0, s.indexer.pending())
        val first = sql("SELECT rowid FROM entry WHERE entry_id='first'") { it.getLong(1) }.single()
        assertEquals(listOf(first), sql("SELECT rowid FROM word_partial") { it.getLong(1) }, "its body is not here yet: read as without an index")
        assertEquals(listOf(first), matching("apiexampletestfirst"))
        s.store.putBody(s.epoch, "first", BodyPart.RESPONSE, "{\"name\":\"item-12-3\"}".byteInputStream())
        assertEquals(1, s.indexer.pending())
        s.indexer.step()
        assertEquals(listOf(first), matching("item123"))
        assertEquals(emptyList(), sql("SELECT rowid FROM word_partial") { it.getLong(1) })
    }

    @Test
    fun `when the app's schemas change, decoded bodies' words are written again`() {
        val s = setup(decoded = "Cart")
        s.store.putEntries(s.epoch, listOf(call("p", 4, binary = true), call("j", 0)))
        s.store.putBody(s.epoch, "p", BodyPart.RESPONSE, byteArrayOf(8, 1, 16, 2).inputStream())
        while (s.indexer.step() > 0) Unit
        assertTrue(matching("cartv1").isNotEmpty())
        s.schemas = "v2"
        assertEquals(1, s.indexer.step(), "only the binary one depends on the schemas")
        assertTrue(matching("cartv2").isNotEmpty())
        assertEquals(emptyList(), matching("cartv1"))
    }

    @Test
    fun `a batch stops building words when its time is spent`() {
        var now = 0L
        val s = setup(clock = { now += 7_000_000; now })
        s.store.putEntries(s.epoch, (1..50).map { call("e$it", 0) })
        val written = s.indexer.step(limit = 50, budgetMs = 20)
        assertTrue(written in 1..4, "written $written")
        assertEquals(50 - written, s.indexer.pending())
    }
}
