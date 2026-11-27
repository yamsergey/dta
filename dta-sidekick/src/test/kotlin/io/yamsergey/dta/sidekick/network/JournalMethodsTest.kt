package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.JournalIdentityResult
import io.yamsergey.dta.protocol.network.JournalPageResult
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 011 T008: the journal methods the host reads (contracts/sidekick-journal.md). */
class JournalMethodsTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    /** D-127: the earlier runs are read on the store's writer thread; an acknowledgement is refused ("reading") until that is done. */
    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7, clock = { now++ }).also { assertTrue(it.awaitLoaded()) } }
    private val methods by lazy { NetworkMethods(store, { emptyList() }) { CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE) } }

    @AfterEach
    fun close() = store.close()

    private fun params(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) })

    private fun record(): HttpEntry =
        HttpEntry(store.nextId(), store.runId, now++, "okhttp3", method = "GET", url = "http://h/x", state = HttpState.COMPLETE).also { store.upsert(it) }

    @Test
    fun `identity, then the journal in pages until the end`() {
        val ids = List(7) { record().id }
        val identity = methods.identity()
        assertEquals(7, identity.entries)
        assertTrue(identity.persisted)
        val seen = mutableListOf<String>()
        var page: JournalPageResult = methods.journal(params("limit" to 3))
        while (true) {
            assertEquals(identity.journalId, page.journalId)
            seen += page.records.map { it.id }
            if (page.end) break
            page = methods.journal(params("after" to page.next, "limit" to 3))
        }
        assertEquals(ids, seen)
    }

    @Test
    fun `a body range comes as an attachment with the whole size`() {
        val e = record()
        val bytes = ByteArray(5000) { it.toByte() }
        store.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(bytes, 0, bytes.size); close() }
        val attached = mutableListOf<ByteArray>()
        val result = methods.bodyRange(params("id" to e.id, "part" to "response", "offset" to 1000, "length" to 2000)) { attached += it }
        assertEquals(5000, result.totalBytes)
        assertEquals(2000, result.bytes)
        assertContentEquals(bytes.copyOfRange(1000, 3000), attached.reduce { a, b -> a + b })
    }

    @Test
    fun `bad journal and range requests are refused with the reason`() {
        assertFailsWith<InvalidParamsException> { methods.journal(params("after" to "nonsense")) }
        assertFailsWith<InvalidParamsException> { methods.bodyRange(params("id" to "r1-1:404", "part" to "response", "offset" to 0, "length" to 10)) {} }
        assertFailsWith<InvalidParamsException> { methods.bodyRange(params("id" to record().id, "part" to "response", "offset" to -1, "length" to 10)) {} }
    }

    @Test
    fun `the methods are registered under their wire names`() {
        val registry = MethodRegistry()
        methods.install(registry)
        listOf("network_identity", "network_journal", "network_body_range", "network_history_settings", "network_archived").forEach { assertTrue(registry[it] != null, it) }
        DtaJson.encodeToString(JournalIdentityResult.serializer(), methods.identity())
    }

    @Test
    fun `history settings are read, set, and refused with the reason (066)`() {
        assertEquals(256L shl 20, methods.historySettings(null).keepBytes)
        val set = methods.historySettings(params("keepBytes" to (64L shl 20), "warnBytes" to (128L shl 20)))
        assertEquals(64L shl 20, set.keepBytes)
        assertEquals(128L shl 20, store.settings().warnBytes)
        assertFailsWith<InvalidParamsException> { methods.historySettings(params("keepBytes" to 10)) }
        assertFailsWith<InvalidParamsException> { methods.historySettings(params("keepBytes" to "lots")) }
    }

    @Test
    fun `an acknowledgement is accepted for this journal and refused for another (066)`() {
        record()
        val journalId = methods.identity().journalId
        fun call(vararg pairs: Pair<String, Any>) = methods.archived(params(*pairs))
        assertTrue(call("journalId" to journalId, "hostId" to "h", "through" to "1:1").accepted)
        assertEquals("journal-changed", call("journalId" to "other", "hostId" to "h", "through" to "1:1").reason)
        assertFailsWith<InvalidParamsException> { call("hostId" to "h") }
    }

    /** Feature 068: an entry with one body written as the recorder does (the body first, then the entry saying what it holds). */
    private fun withBody(bytes: ByteArray, binary: Boolean = false, preview: String? = null): HttpEntry {
        val e = HttpEntry(store.nextId(), store.runId, now++, "okhttp3", method = "GET", url = "http://h/x", state = HttpState.COMPLETE)
        store.openBodySink(e.id, BodyPart.RESPONSE)!!.apply { write(bytes, 0, bytes.size); close() }
        val done = e.copy(responseBody = io.yamsergey.dta.protocol.network.BodyInfo(BodyPart.RESPONSE, bytes.size.toLong(), binary = binary,
            completeness = io.yamsergey.dta.protocol.network.Completeness.COMPLETE, preview = preview))
        store.upsert(done)
        return done
    }

    @Test
    fun `journal pages carry bodies of up to 64 KB, not larger ones, nor one its preview holds whole`() {
        val small = withBody("{\"price\": 12}".repeat(30).toByteArray())
        val binary = withBody(byteArrayOf(0, 1, 2, -1), binary = true)
        val large = withBody(ByteArray(70 * 1024) { 'a'.code.toByte() })
        val whole = withBody("ok".toByteArray(), preview = "ok")
        val page = methods.journal(params("limit" to 100))
        val bodies = page.bodies.orEmpty().associateBy { it.entryId }
        assertEquals(setOf(small.id, binary.id), bodies.keys)
        assertEquals("{\"price\": 12}".repeat(30), bodies.getValue(small.id).text)
        assertContentEquals(byteArrayOf(0, 1, 2, -1), bodies.getValue(binary.id).bytes())
        assertTrue(large.id !in bodies && whole.id !in bodies)
    }

    /** D-127: while the store reads the earlier runs it refuses an acknowledgement ("reading"); once it has, it accepts. */
    @Test
    fun `an acknowledgement waits for the earlier runs to be read`() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = NetworkStore(StorageDir(File(dir, "slow")), pid = 8, clock = { now++ }, beforeLoad = { reading.countDown(); release.await() })
        try {
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val m = NetworkMethods(slow, { emptyList() }) { CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE) }
            val ack = params("journalId" to slow.identity().journalId, "hostId" to "h", "through" to "1:1")
            assertEquals("reading", m.archived(ack).reason)
            release.countDown()
            assertTrue(slow.awaitLoaded())
            assertTrue(m.archived(ack).accepted)
        } finally { release.countDown(); slow.close() }
    }
}
