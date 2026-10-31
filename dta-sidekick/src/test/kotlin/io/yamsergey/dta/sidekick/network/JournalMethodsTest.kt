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
    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7, clock = { now++ }) }
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
        listOf("network_identity", "network_journal", "network_body_range").forEach { assertTrue(registry[it] != null, it) }
        DtaJson.encodeToString(JournalIdentityResult.serializer(), methods.identity())
    }
}
