package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.HistoryDefaults
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 066: the device keeps only what this computer has not archived (research R1, R2, R4). */
class NetworkStoreArchiveTrimTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store(pid: Int = 42, segmentBytes: Long = 4L * 1024 * 1024) =
        NetworkStore(StorageDir(dir), pid = pid, clock = { now++ }, bounds = StoreBounds(segmentBytes = segmentBytes))
            .also { opened += it; assertTrue(it.awaitLoaded()) }

    private fun http(s: NetworkStore, state: HttpState = HttpState.COMPLETE) =
        HttpEntry(id = s.nextId(), runId = s.runId, timeMs = now++, library = "okhttp3", method = "GET", url = "http://h/x", state = state)

    // ---- A2: settings ------------------------------------------------------------------

    @Test
    fun `the budget and warning size default to 256 and 512 MB`() {
        val s = store()
        assertEquals(HistoryDefaults.KEEP_BYTES, s.settings().keepBytes)
        assertEquals(HistoryDefaults.WARN_BYTES, s.settings().warnBytes)
    }

    @Test
    fun `settings are kept across restarts and a clear`() {
        val s = store()
        s.setSettings(keepBytes = 32L shl 20, warnBytes = 64L shl 20)
        s.close(); opened.clear()
        val again = store()
        assertEquals(32L shl 20, again.settings().keepBytes)
        assertEquals(64L shl 20, again.settings().warnBytes)
        again.upsert(http(again))
        again.clear()
        assertEquals(32L shl 20, again.settings().keepBytes)
        assertEquals(64L shl 20, again.coverage().warnBytes)
    }

    @Test
    fun `one value can be set alone, and refused values say why`() {
        val s = store()
        assertEquals(HistoryDefaults.WARN_BYTES, s.setSettings(keepBytes = 100L shl 20).warnBytes)
        assertEquals(100L shl 20, s.settings().keepBytes)
        val small = assertFailsWith<InvalidParamsException> { s.setSettings(keepBytes = 1024) }
        assertTrue(small.message!!.contains("16 MB"), small.message)
        assertFailsWith<InvalidParamsException> { s.setSettings(warnBytes = 50L shl 20) }
        assertEquals(100L shl 20, s.settings().keepBytes, "a refused change changes nothing")
    }
}
