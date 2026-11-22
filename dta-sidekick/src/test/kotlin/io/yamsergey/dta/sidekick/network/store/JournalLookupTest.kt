package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.network.NetworkMethods
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-037: looking an entry up never reads the whole journal. An id the journal cannot hold (not
 * `run:seq`, an unknown run, a sequence no segment holds) is ruled out from the segments' id index
 * without reading a line; a known one is read from its segment only. A whole-journal read took the
 * app's CPU for seconds under load and delayed its own feed.
 */
class JournalLookupTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store() = NetworkStore(
        storage = StorageDir(dir), pid = 42, clock = { now++ }, bounds = StoreBounds(segmentBytes = 4_000), recentWindow = 10,
    ).also { opened += it; assertTrue(it.awaitLoaded()) }

    /** 600 finished exchanges: many segments; all but the newest few only in the journal. */
    private fun filled(): Pair<NetworkStore, List<String>> {
        val s = store()
        val ids = (1..600).map { i ->
            HttpEntry(s.nextId(), s.runId, now++, "okhttp3", method = "GET", url = "http://h/$i", state = HttpState.COMPLETE, status = 200).also { s.upsert(it) }.id
        }
        s.flush()
        return s to ids
    }

    @Test
    fun `an id the journal cannot hold is ruled out without reading a line`() {
        val (s, ids) = filled()
        val total = s.linesReadForTest
        for (id in listOf("no-such-entry", "device", "${s.runId}:999999", "r1-9:5", "${s.runId}:abc")) {
            assertNull(s.get(id), id)
        }
        assertEquals(total, s.linesReadForTest, "no journal line was read")
        assertTrue(ids.size > 0)
    }

    @Test
    fun `a known entry is read from its own segment only`() {
        val (s, ids) = filled()
        val before = s.linesReadForTest
        val old = s.get(ids[5])
        assertEquals(ids[5], old?.id)
        val read = s.linesReadForTest - before
        assertTrue(read in 1..100, "read $read lines for one entry of 600")
    }

    @Test
    fun `network_entry of a request reads neither the whole journal nor a list of it`() {
        val (s, ids) = filled()
        val methods = NetworkMethods(s) { CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE) }
        val before = s.linesReadForTest
        assertEquals(ids[7], methods.entry(JsonObject(mapOf("id" to JsonPrimitive(ids[7])))).entry.id)
        assertTrue(s.linesReadForTest - before <= 100, "read ${s.linesReadForTest - before} lines")
        val unknown = runCatching { methods.entry(JsonObject(mapOf("id" to JsonPrimitive("no-such-entry")))) }
        assertTrue(unknown.isFailure, "an unknown id is refused")
        assertTrue(s.linesReadForTest - before <= 100, "the unknown id read nothing more")
    }
}
