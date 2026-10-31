package io.yamsergey.dta.daemon.network

import com.google.protobuf.Field
import io.yamsergey.dta.daemon.network.proto.BodyDecoding
import io.yamsergey.dta.daemon.network.proto.DecodingRule
import io.yamsergey.dta.daemon.network.proto.Selection
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.MatchLocation
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 011 T024 (US6): search matches decoded protobuf fields, with the field as the location. */
class DecodedSearchTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private val rule = DecodingRule { t -> if (t.url.endsWith("/field")) Selection(Field.getDescriptor(), "protobuf", "s", "m1") else null }

    private fun record(app: FakeJournalApp, n: Int, url: String, message: Field) {
        val bytes = message.toByteArray()
        val e = HttpEntry("r1-1:$n", "r1-1", n.toLong(), "okhttp3", method = "POST", url = url, state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, bytes.size.toLong(), contentType = "application/x-protobuf", binary = true, completeness = Completeness.COMPLETE))
        app.record(e)
        app.bodies[e.id to BodyPart.RESPONSE] = bytes
    }

    private suspend fun host(): Pair<FakeJournalApp, HostIndex> {
        val app = FakeJournalApp()
        record(app, 1, "https://api/field", Field.newBuilder().setName("cart-item-42").setNumber(7).build())
        record(app, 2, "https://api/other", Field.newBuilder().setName("cart-item-42").build())
        val store = HostStore(dir).also { stores += it }
        return app to HostIndex(store, DeviceIdentity("dev1"), "io.example", decoding = { BodyDecoding(listOf(rule)) }) { app }
    }

    @Test
    fun `a decoded field value matches, with its path`() = runBlocking<Unit> {
        val (_, host) = host()
        val page = host.list(NetworkQuery(filter = NetworkFilter(bodyContains = "item-42")))
        assertEquals(setOf("r1-1:1", "r1-1:2"), page.entries.map { it.id }.toSet(), "the unmapped one still matches as bytes")
        assertEquals(listOf(MatchLocation.RESPONSE_BODY_DECODED), page.matches!!.getValue("r1-1:1"))
        assertEquals(listOf("name"), page.fields!!.getValue("r1-1:1"))
        assertEquals(listOf(MatchLocation.RESPONSE_BODY_BINARY), page.matches!!.getValue("r1-1:2"))
    }

    @Test
    fun `a protobuf body without a schema is stated as not searched decoded`() = runBlocking<Unit> {
        val (_, host) = host()
        val notSearched = host.list(NetworkQuery(filter = NetworkFilter(bodyContains = "item-42"))).coverage.notSearched.orEmpty()
        assertTrue(notSearched.any { "without a schema" in it && "(1)" in it }, "$notSearched")
    }

    @Test
    fun `archived bodies are searched decoded too`() = runBlocking<Unit> {
        val (app, host) = host()
        host.list(NetworkQuery(filter = NetworkFilter(bodyContains = "x"))) // copies the bodies
        app.reset("j2", "cleared")
        host.list(NetworkQuery())
        val page = host.list(NetworkQuery(archive = ArchiveScope(all = true), filter = NetworkFilter(bodyContains = "item-42")))
        assertEquals(listOf("name"), page.fields!!.getValue("r1-1:1"))
    }
}
