package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.FeedBatch
import io.yamsergey.dta.protocol.network.FeedEnded
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.Event
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 068 (research R6): the app's events drive the archive; only large bodies are copied by range. */
class ArchiveFeedTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun index(app: FakeJournalApp) = HostIndex(HostStore(dir).also { stores += it }, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }

    private var n = 0
    private fun http(app: FakeJournalApp, size: Int): HttpEntry {
        val id = "r1-1:${++n}"
        val body = ByteArray(size) { 'a'.code.toByte() }
        val e = HttpEntry(id, "r1-1", 1_000L + n, "okhttp3", method = "GET", url = "https://api.example.test/v1/$n", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, size.toLong(), contentType = "text/plain", completeness = Completeness.COMPLETE, preview = "a".repeat(minOf(size, 256)).takeIf { size > 256 }))
        app.record(e)
        app.bodies[id to BodyPart.RESPONSE] = body
        return e
    }

    private fun event(batch: FeedBatch) = Event(FeedBatch.EVENT, 1, DtaJson.encodeToJsonElement(FeedBatch.serializer(), batch))

    @Test
    fun `a feed event goes into the archive, and only large bodies are copied by range`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        http(app, 300)
        val host = index(app)
        host.openFeed()
        val small = http(app, 2_000)
        val large = http(app, 70 * 1024)
        host.onAppEvent(event(app.batch(1)))
        assertEquals(listOf(large.id), host.pendingBodies().map { it.entryId }, "the small body came with its record")
        CopyWorker(host).runOnce()
        assertTrue(app.requests.filter { it == "network_body_range" }.isNotEmpty())
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals(setOf(small.id, large.id, "r1-1:1"), host.list(NetworkQuery(limit = 10)).entries.map { it.id }.toSet())
    }

    @Test
    fun `an end, dropped events or another connection end the subscription`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        http(app, 300)
        val host = index(app)
        host.openFeed()
        host.onAppEvent(Event(FeedEnded.EVENT, 2, DtaJson.encodeToJsonElement(FeedEnded.serializer(), FeedEnded(app.feedId!!, FeedEnded.OVERFLOW, "0:1"))))
        assertEquals(false, host.feedLive)
        host.openFeed()
        host.onAppEvent(Event("network.events-dropped", 3, null))
        assertEquals(false, host.feedLive, "a lost batch may be among them")
        host.openFeed()
        host.connectionChanged()
        assertEquals(false, host.feedLive)
    }

    @Test
    fun `a body its preview holds whole is archived when its record comes, never waiting to be copied`() = runBlocking<Unit> {
        val app = FakeJournalApp().apply { attachBodies = true }
        val host = index(app)
        host.openFeed()
        val id = "r1-1:${++n}"
        app.record(io.yamsergey.dta.protocol.network.WsMessageEntry(id, "r1-1", 5, "okhttp3-ws", connectionId = "r1-1:0",
            direction = io.yamsergey.dta.protocol.network.Direction.IN, opcode = io.yamsergey.dta.protocol.network.MessageOpcode.TEXT,
            payload = BodyInfo(BodyPart.MESSAGE, 4, completeness = Completeness.COMPLETE, preview = "tick")))
        host.onAppEvent(event(app.batch(0)))
        assertEquals(emptyList(), host.pendingBodies())
        assertEquals("tick", host.storedBody(id, BodyPart.MESSAGE)?.second?.decodeToString())
    }
}
