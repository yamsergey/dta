package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.ConnectionLostException
import io.yamsergey.dta.daemon.connection.RpcResult
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** 011 T017 (US7): bodies reach the host as soon as entries finish, within a budget, resumably. */
class CopyWorkerTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private var now = 0L
    private val slept = mutableListOf<Long>()

    /** Virtual time: sleeping advances the clock; each chunk request costs [chunkMs]. */
    private var chunkMs = 10L

    private inner class TimedApp : FakeJournalApp() {
        val ranges = mutableListOf<Pair<String, Long>>()
        var failAt: Int? = null
        override suspend fun requestFull(method: String, params: JsonObject?, timeout: Duration?): RpcResult {
            if (method == "network_body_range") {
                ranges += (params!!["id"] as JsonPrimitive).content to (params["offset"] as JsonPrimitive).content.toLong()
                now += chunkMs
                if (failAt == ranges.size) throw ConnectionLostException("app disconnected")
            }
            return super.requestFull(method, params, timeout)
        }
    }

    private fun entry(app: FakeJournalApp, n: Int, size: Int, state: HttpState = HttpState.COMPLETE): ByteArray {
        val bytes = ByteArray(size) { (it * n).toByte() }
        val e = HttpEntry("r1-1:$n", "r1-1", n.toLong(), "okhttp3", method = "GET", url = "https://api/$n", state = state, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, size.toLong(), completeness = Completeness.COMPLETE, binary = true))
        app.record(e)
        app.bodies[e.id to BodyPart.RESPONSE] = bytes
        return bytes
    }

    private fun setUp(app: FakeJournalApp, bytesPerSecond: Long = 8L * 1024 * 1024): Triple<HostStore, HostIndex, CopyWorker> {
        val store = HostStore(dir).also { stores += it }
        val index = HostIndex(store, DeviceIdentity("dev1"), "io.example", clock = { now }) { app }
        val worker = CopyWorker(index, bytesPerSecond = bytesPerSecond, clock = { now }, sleep = { ms -> slept += ms; now += ms })
        return Triple(store, index, worker)
    }

    @Test
    fun `bodies of finished entries are copied without a query, in-flight ones wait`() = runBlocking<Unit> {
        val app = TimedApp()
        val body = entry(app, 1, 600_000)
        entry(app, 2, 1000, state = HttpState.PENDING)
        val (store, index, worker) = setUp(app)
        index.list(NetworkQuery())
        worker.runOnce()
        val epoch = store.liveEpoch("dev1", "io.example")!!
        assertContentEquals(body, store.openBody(store.bodyRef(epoch.id, "r1-1:1", BodyPart.RESPONSE)!!.sha256!!)!!.readBytes())
        assertEquals(null, store.bodyRef(epoch.id, "r1-1:2", BodyPart.RESPONSE), "in flight: not yet")
        assertEquals(listOf(0L, 262_144L, 524_288L), app.ranges.map { it.second }, "256 KB chunks: an interactive request waits one chunk at most")
        assertEquals(null, index.list(NetworkQuery()).coverage.bodiesPending)
    }

    @Test
    fun `the budget limits throughput`() = runBlocking<Unit> {
        val app = TimedApp()
        entry(app, 1, 3 * 1024 * 1024)
        val (_, index, worker) = setUp(app, bytesPerSecond = 1024L * 1024)
        index.list(NetworkQuery())
        val start = now
        worker.runOnce()
        assertTrue(now - start >= 2_800, "3 MB at 1 MB/s takes about 3 s, took ${now - start} ms")
    }

    @Test
    fun `slower round-trips halve the budget, recovery is gradual`() = runBlocking<Unit> {
        val app = TimedApp()
        entry(app, 1, 4 * 1024 * 1024)
        val (_, index, worker) = setUp(app, bytesPerSecond = 64L * 1024 * 1024)
        index.list(NetworkQuery())
        val initial = worker.rate
        chunkMs = 10
        worker.runOnce()
        entry(app, 2, 4 * 1024 * 1024)
        index.list(NetworkQuery())
        chunkMs = 100
        worker.runOnce()
        assertTrue(worker.rate < initial / 2 + 1, "rate ${worker.rate} after slow chunks, was $initial")
    }

    @Test
    fun `a disconnect resumes from the last chunk`() = runBlocking<Unit> {
        val app = TimedApp().apply { failAt = 2 }
        val body = entry(app, 1, 600_000)
        val (store, index, worker) = setUp(app)
        index.list(NetworkQuery())
        worker.runOnce()
        worker.runOnce()
        assertEquals(listOf(0L, 262_144L, 262_144L, 524_288L), app.ranges.map { it.second }, "the failed chunk is asked again, not the first")
        val epoch = store.liveEpoch("dev1", "io.example")!!
        assertContentEquals(body, store.openBody(store.bodyRef(epoch.id, "r1-1:1", BodyPart.RESPONSE)!!.sha256!!)!!.readBytes())
    }

    @Test
    fun `a body the device no longer has is stated, not lost silently`() = runBlocking<Unit> {
        val app = TimedApp()
        entry(app, 1, 1000)
        app.bodies.clear()
        val (store, index, worker) = setUp(app)
        index.list(NetworkQuery())
        worker.runOnce()
        val ref = assertNotNull(store.bodyRef(store.liveEpoch("dev1", "io.example")!!.id, "r1-1:1", BodyPart.RESPONSE))
        assertTrue(ref.notArchived!!.isNotBlank())
        assertEquals(1, index.list(NetworkQuery()).coverage.bodiesNotArchived)
    }

    @Test
    fun `bodies not yet copied are counted`() = runBlocking<Unit> {
        val app = TimedApp()
        entry(app, 1, 1000)
        entry(app, 2, 1000)
        val (_, index, _) = setUp(app)
        assertEquals(2, index.list(NetworkQuery()).coverage.bodiesPending)
    }
}
