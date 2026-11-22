package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.LinkEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 071 (FR-014, research R2): entries per process run, kept by triggers (format v4). */
class RunCountersTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun store() = HostStore(dir).also { stores += it }

    private val device = DeviceIdentity(key = "dev1", serial = "emulator-5554", model = "Pixel 8", release = "16")
    private val v14 = InstallationInfo(versionName = "1.4.0", versionCode = 14, lastUpdateMs = 100, signingSha256 = "aa")

    private fun http(run: String, n: Int, time: Long = n.toLong()) =
        HttpEntry(id = "$run:$n", runId = run, timeMs = time, library = "okhttp3", method = "GET", url = "https://api.example.test/$n", state = HttpState.COMPLETE, status = 200)

    private fun link(run: String, n: Int) = LinkEntry(id = "$run:L$n", runId = run, timeMs = 25, uri = "app://x/$n", activity = "Main", delivery = io.yamsergey.dta.protocol.network.LinkDelivery.CREATED)

    @Test
    fun `each run counts its entries and links and knows where it starts`() {
        val s = store()
        val e = s.openEpoch(device, "io.example", v14, "j1", 1)
        s.putEntries(e.id, listOf(http("r1", 1, 10), http("r1", 2, 20), link("r1", 3)))
        s.putEntries(e.id, listOf(http("r2", 1, 30), http("r2", 2, 40)))
        // An upsert of an earlier run's entry (it completed after the restart) counts nothing again.
        s.putEntries(e.id, listOf(http("r1", 2, 20).copy(status = 500)))
        val runs = s.runs(e.id).associateBy { it.runId }
        assertEquals(3, runs.getValue("r1").entries)
        assertEquals(1, runs.getValue("r1").links)
        assertEquals(2, runs.getValue("r2").entries)
        assertEquals(10, runs.getValue("r1").firstMs)
        assertEquals(30, runs.getValue("r2").firstMs)
        val seqOfR2 = s.entries(e.id).indexOfFirst { it.runId == "r2" }.toLong()
        assertEquals(seqOfR2, runs.getValue("r2").firstSeq - runs.getValue("r1").firstSeq)
        assertEquals(listOf("r2", "r1"), s.runs(e.id).map { it.runId }, "newest first")
    }

    @Test
    fun `deleting an epoch removes its runs`() {
        val s = store()
        val e = s.openEpoch(device, "io.example", v14, "j1", 1)
        s.putEntries(e.id, listOf(http("r1", 1)))
        s.closeEpoch(e.id, 5, io.yamsergey.dta.protocol.network.EpochCloseReason.CLEARED, 0)
        s.delete(DeleteTarget.Epoch(e.id))
        assertEquals(emptyList(), s.runs(e.id))
    }

    @Test
    fun `an archive of format 3 is started over (the user's call, 2026-11-22)`() {
        val s = store()
        val e = s.openEpoch(device, "io.example", v14, "j1", 1)
        s.putEntries(e.id, (1..50).map { http(if (it <= 20) "r1" else "r2", it) })
        s.close(); stores.clear()
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "dta.db").path}").use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TRIGGER run_added"); st.execute("DROP TRIGGER run_removed"); st.execute("DROP TABLE run")
                st.execute("PRAGMA user_version=3")
            }
        }
        val again = store()
        assertEquals("the archive was in format 3, from before the runs' counters (2026-11-22); it was cleared", again.startedOver)
        assertEquals(0, again.entryCount(e.id))
        assertEquals(emptyList(), again.runs(e.id))
        assertNull(again.liveEpoch("dev1", "io.example"))
    }
}
