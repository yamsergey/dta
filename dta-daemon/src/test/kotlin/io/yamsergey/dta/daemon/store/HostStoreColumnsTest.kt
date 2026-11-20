package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.daemon.network.ArchiveFixtures
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.LinkEntry
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 069 A1: what a query narrows by is written with each entry, from the entry itself. */
class HostStoreColumnsTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store() = HostStore(dir).also { stores += it }
    private val device = DeviceIdentity("dev1", "emulator-5554")
    private val v14 = InstallationInfo("1.4.0", 14, 100, "aa")

    private fun link(id: String, time: Long, uri: String) =
        LinkEntry(id, "r1-1", time, uri = uri, activity = "MainActivity", delivery = io.yamsergey.dta.protocol.network.LinkDelivery.COLD_START)

    private fun <T> sql(query: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "dta.db").path}").use { c ->
            c.prepareStatement(query).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
            }
        }

    /** What each column must hold, read from the entry the way the query's evaluator reads it. */
    private fun expected(e: NetworkEntry): List<Any?> = listOf(
        (e as? HttpEntry)?.method,
        when (e) {
            is HttpEntry -> NetworkFilterEval.hostOf(e.url)
            is WsConnectionEntry -> NetworkFilterEval.hostOf(e.url)
            is LinkEntry -> NetworkFilterEval.hostOf(e.uri)
            is WsMessageEntry -> null
        },
        when (e) {
            is WsMessageEntry -> e.connectionId
            is WsConnectionEntry -> e.id
            else -> null
        },
        (e as? WsMessageEntry)?.direction?.name,
        e.library,
        e.runId,
        when (e) {
            is HttpEntry -> e.state.name
            is WsConnectionEntry -> e.state.name
            else -> null
        },
        if (NetworkHistoryView.bodiesOf(e).isNotEmpty()) 1 else 0,
    )

    @Test
    fun `every write fills the columns a query narrows by, from the entry`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", v14, "j1", 1)
        val random = Random(7)
        val entries = (0 until 500).map { ArchiveFixtures.entry(it * 37 + random.nextInt(37), random) } +
            link("r1-1:link", 5, "https://links.example.test/open?x=1")
        s.putEntries(epoch.id, entries)
        val stored = sql("SELECT entry_id, method, host, connection_id, direction, library, run_id, state, has_body FROM entry WHERE epoch_id=?", epoch.id) { r ->
            r.getString(1) to (2..9).map { r.getObject(it)?.let { v -> if (v is Number) v.toInt() else v } }
        }.toMap()
        entries.forEach { e -> assertEquals(expected(e), stored.getValue(e.id), e.id) }
    }

    @Test
    fun `an epoch keeps how many of its entries are links`() {
        val s = store()
        val epoch = s.openEpoch(device, "io.example", v14, "j1", 1)
        val random = Random(2)
        s.putEntries(epoch.id, listOf(ArchiveFixtures.entry(1, random), link("r1-1:l1", 5, "app://a"), link("r1-1:l2", 6, "app://b")))
        s.putEntries(epoch.id, listOf(link("r1-1:l1", 7, "app://a2")))
        assertEquals(listOf(3 to 2), sql("SELECT entries, links FROM epoch WHERE id=?", epoch.id) { it.getInt(1) to it.getInt(2) })
        s.closeEpoch(epoch.id, 9, io.yamsergey.dta.protocol.network.EpochCloseReason.CLEARED, 0)
        s.delete(DeleteTarget.Epoch(epoch.id))
        assertEquals(emptyList(), sql("SELECT links FROM epoch WHERE id=?", epoch.id) { it.getInt(1) })
    }

    @Test
    fun `an archive of format 1 is started over`() {
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "dta.db").path}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE entry(x)"); it.execute("PRAGMA user_version=1") }
        }
        val s = store()
        assertTrue(s.startedOver != null)
        assertEquals(listOf(2), sql("PRAGMA user_version") { it.getInt(1) })
    }
}
