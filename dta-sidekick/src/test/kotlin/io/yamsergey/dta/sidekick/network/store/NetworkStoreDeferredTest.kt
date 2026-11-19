package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Found under load (2026-11-19): every socket message wrote the whole connection record again
 * (its stack and headers, ~1.5 KB) for its message count: four fifths of the journal. A change
 * of the count alone is written at most once a second; the count in memory is always exact.
 */
class NetworkStoreDeferredTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val opened = mutableListOf<NetworkStore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store() = NetworkStore(StorageDir(dir), pid = 42, clock = { now }).also { opened += it; assertTrue(it.awaitLoaded()) }

    /** The connection's records as written (read from the files: reading the journal writes the last count first). */
    private fun records(s: NetworkStore, id: String): List<WsConnectionEntry> {
        s.flushQueuedForTest()
        return File(dir, "segments").listFiles()!!.sortedBy { it.name }.flatMap { it.readLines() }
            .map { io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromString(EntryRecord.serializer(), it).entry }
            .filter { it.id == id }.map { it as WsConnectionEntry }
    }

    @Test
    fun `reading the journal gives the last count`() {
        val s = store()
        val open = WsConnectionEntry(s.nextId(), s.runId, now, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
        s.upsert(open)
        repeat(20) { n -> s.upsertCount(open.copy(messageCount = n + 1)) }
        assertEquals(20, (s.journal(null, 1000).records.last { it.id == open.id } as WsConnectionEntry).messageCount)
    }

    @Test
    fun `a count alone is written at most once a second, and exact in memory`() {
        val s = store()
        val open = WsConnectionEntry(s.nextId(), s.runId, now, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
        s.upsert(open)
        repeat(500) { n -> s.upsertCount(open.copy(messageCount = n + 1)); now += 1 } // 500 messages in half a second
        assertEquals(500, (s.get(open.id) as WsConnectionEntry).messageCount, "memory has every message")
        records(s, open.id).let { assertTrue(it.size <= 2, "not a record per message: ${it.map { r -> r.messageCount }}") }
        // The last count reaches the journal a second later, with no more traffic.
        now += 1_000
        s.writeDeferredForTest()
        assertEquals(500, records(s, open.id).last().messageCount)
    }

    @Test
    fun `a change of state is written at once, and a flush writes the last count`() {
        val s = store()
        val open = WsConnectionEntry(s.nextId(), s.runId, now, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN)
        s.upsert(open)
        repeat(10) { n -> s.upsertCount(open.copy(messageCount = n + 1)) }
        s.flush()
        assertEquals(10, records(s, open.id).last().messageCount, "flush writes what memory has")
        repeat(5) { n -> s.upsertCount(open.copy(messageCount = 11 + n)) }
        s.upsert(open.copy(messageCount = 15, state = WsState.CLOSED, closedAtMs = now))
        val last = records(s, open.id).last()
        assertEquals(WsState.CLOSED to 15, last.state to last.messageCount)
        // A deferred count older than the close is never written after it.
        now += 2_000
        s.writeDeferredForTest()
        assertEquals(WsState.CLOSED, records(s, open.id).last().state)
    }
}
