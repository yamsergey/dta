package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.connection.RpcResult
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.BodyRangeResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.EntryKind
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.JournalIdentityResult
import io.yamsergey.dta.protocol.network.JournalPageResult
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.RunScope
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * An app's journal in memory, served as the sidekick serves it (contracts/sidekick-journal.md):
 * one record per change, pages after a cursor, bodies by range.
 */
open class FakeJournalApp(var journalId: String = "j1", var persisted: Boolean = true) : AppConnection {
    val records = mutableListOf<NetworkEntry>()
    val bodies = HashMap<Pair<String, BodyPart>, ByteArray>()
    var runId = "r1-1"
    var reason: String? = "storage-new"
    var listCalls = 0
    var installation: io.yamsergey.dta.protocol.rpc.AppInstallation? = io.yamsergey.dta.protocol.rpc.AppInstallation("1.4.0", 14, 1, 100, "aa")
    val requests = mutableListOf<String>()

    fun record(entry: NetworkEntry) = records.add(entry)

    /** The device forgot: a new journal, empty, with [why] (`cleared`, `storage-new`). */
    fun reset(newId: String, why: String) {
        journalId = newId
        reason = why
        records.clear()
        bodies.clear()
    }

    private fun latest(): List<NetworkEntry> = LinkedHashMap<String, NetworkEntry>().apply { records.forEach { put(it.id, it) } }.values.toList()

    fun context() = HistoryContext(runId, Coverage(searched = latest().size, stored = latest().size, persisted = persisted), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE))

    /** The sidekick's own answer over the same history: the reference the host must equal. */
    fun deviceAnswer(query: NetworkQuery): NetworkPage = NetworkHistoryView({ latest() }, { id -> latest().firstOrNull { it.id == id } }, ::context, bodyBytes = { e, part ->
        val info = NetworkHistoryView.bodyOf(e, part)
        info?.let { bodies[e.id to part]?.let { BodyCodec.decode(it, info.encoding).let { d -> NetworkFilterEval.BodyBytes(d.bytes, d.decoded) } } }
    }).list(query)

    override val hello = HelloResult(PROTOCOL_VERSION, "dta-sidekick", "0.11.0", "io.example")
    override val events: SharedFlow<Event> = MutableSharedFlow()
    override val isOpen = true

    override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement = requestFull(method, params, timeout).result

    override suspend fun requestFull(method: String, params: JsonObject?, timeout: Duration?): RpcResult {
        requests += method
        fun str(k: String) = (params?.get(k) as? JsonPrimitive)?.content
        return when (method) {
            "network_identity" -> RpcResult(DtaJson.encodeToJsonElement(JournalIdentityResult.serializer(), JournalIdentityResult(journalId, reason, latest().size, persisted, context())))
            "network_journal" -> {
                val from = str("after")?.toInt() ?: 0
                val limit = str("limit")?.toInt() ?: 500
                val page = records.drop(from).take(limit)
                val next = from + page.size
                RpcResult(DtaJson.encodeToJsonElement(JournalPageResult.serializer(), JournalPageResult(journalId, page, "$next", next >= records.size)))
            }
            "network_body_range" -> {
                val part = BodyPart.entries.first { DtaJson.encodeToJsonElement(BodyPart.serializer(), it).let { j -> (j as JsonPrimitive).content } == str("part") }
                val bytes = bodies[str("id")!! to part]!!
                val offset = str("offset")!!.toInt()
                val slice = bytes.copyOfRange(offset, minOf(bytes.size, offset + str("length")!!.toInt()))
                RpcResult(DtaJson.encodeToJsonElement(BodyRangeResult.serializer(), BodyRangeResult(bytes.size.toLong(), offset.toLong(), slice.size.toLong())), listOf(slice))
            }
            "network_list" -> {
                listCalls++
                RpcResult(DtaJson.encodeToJsonElement(NetworkPage.serializer(), deviceAnswer(NetworkQuery.parse(params))))
            }
            "identity" -> RpcResult(DtaJson.encodeToJsonElement(IdentityReport.serializer(), IdentityReport(PROTOCOL_VERSION, "0.11.0", "io.example", emptyList(), installation)))
            else -> error("unexpected $method")
        }
    }

    override suspend fun close() = Unit
}

/** 011 T010–T014 (US2): the host answers network queries as the app would. */
class HostIndexTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private fun index(app: FakeJournalApp, store: HostStore = HostStore(dir).also { stores += it }) =
        HostIndex(store, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }

    /** The app's answer, without what only the host adds (bodies it has not copied yet, US7). */
    private suspend fun HostIndex.answer(q: NetworkQuery): NetworkPage =
        list(q).let { it.copy(coverage = it.coverage.copy(bodiesPending = null, bodiesNotArchived = null)) }

    private var t = 1_000L

    private fun http(app: FakeJournalApp, n: Int, url: String = "https://api/x/$n", state: HttpState = HttpState.COMPLETE, status: Int = 200, body: String? = null, run: String = app.runId) =
        HttpEntry("$run:$n", run, t++, "okhttp3", method = if (n % 2 == 0) "GET" else "POST", url = url, state = state, status = status,
            responseBody = body?.let { BodyInfo(BodyPart.RESPONSE, it.length.toLong(), completeness = Completeness.COMPLETE, contentType = "application/json") })
            .also { e -> app.record(e); body?.let { app.bodies[e.id to BodyPart.RESPONSE] = it.toByteArray() } }

    /** A mixed corpus: two runs, HTTP of several statuses and methods, a socket, a late update, bodies. */
    private fun corpus(app: FakeJournalApp) {
        repeat(15) { http(app, it + 1, run = "r0-9", status = 200 + (it % 3) * 100) }
        app.runId = "r1-1"
        repeat(30) { http(app, it + 1, status = if (it % 4 == 0) 404 else 200, body = if (it % 5 == 0) """{"cart":"item-$it"}""" else null) }
        app.record(WsConnectionEntry("r1-1:100", "r1-1", t++, "okhttp3", null, "wss://chat/socket", WsState.OPEN))
        app.record((app.records.first { it.id == "r1-1:3" } as HttpEntry).copy(status = 500))
        http(app, 31, state = HttpState.PENDING)
    }

    private val queries = listOf(
        NetworkQuery(),
        NetworkQuery(limit = 7),
        NetworkQuery(limit = 7, order = Order.OLDEST_FIRST),
        NetworkQuery(filter = NetworkFilter(status = "4xx")),
        NetworkQuery(filter = NetworkFilter(status = "500")),
        NetworkQuery(filter = NetworkFilter(method = "POST", runs = RunScope.CURRENT)),
        NetworkQuery(filter = NetworkFilter(url = "x/2")),
        NetworkQuery(filter = NetworkFilter(kinds = listOf(EntryKind.WS_CONNECTION))),
        NetworkQuery(filter = NetworkFilter(status = "pending")),
        NetworkQuery(filter = NetworkFilter(bodyContains = "item-10")),
        NetworkQuery(filter = NetworkFilter(sinceMs = 1_020, untilMs = 1_030)),
    )

    @Test
    fun `every query of the corpus gets the device's answer`() = runBlocking<Unit> {
        val app = FakeJournalApp().also(::corpus)
        val host = index(app)
        queries.forEach { q -> assertEquals(app.deviceAnswer(q), host.answer(q), "query $q") }
        // Paging through with the cursor gives the same pages.
        val first = host.list(NetworkQuery(limit = 10))
        assertEquals(app.deviceAnswer(NetworkQuery(limit = 10, before = first.next)), host.answer(NetworkQuery(limit = 10, before = first.next)))
        assertEquals(0, app.listCalls, "the device answered nothing itself")
    }

    @Test
    fun `entry detail and a socket's messages come from the host`() = runBlocking<Unit> {
        val app = FakeJournalApp().also(::corpus)
        val host = index(app)
        assertEquals(500, (host.entry("r1-1:3").entry as HttpEntry).status, "the latest record wins")
        assertEquals("wss://chat/socket", (host.entry("r1-1:100").entry as WsConnectionEntry).url)
    }

    @Test
    fun `new records are caught up before each answer`() = runBlocking<Unit> {
        val app = FakeJournalApp().also(::corpus)
        val host = index(app)
        val before = host.list(NetworkQuery()).total
        http(app, 40)
        assertEquals(before + 1, host.list(NetworkQuery()).total)
        assertEquals(app.deviceAnswer(NetworkQuery()), host.answer(NetworkQuery()))
    }

    @Test
    fun `catch-up resumes from the cursor after a disconnect and a daemon restart`() = runBlocking<Unit> {
        val app = FakeJournalApp().also(::corpus)
        index(app).list(NetworkQuery())
        stores.forEach { it.close() }; stores.clear()
        http(app, 41); http(app, 42)
        val again = index(app)
        val requestsBefore = app.requests.size
        assertEquals(app.deviceAnswer(NetworkQuery()), again.answer(NetworkQuery()))
        assertTrue(app.requests.drop(requestsBefore).count { it == "network_journal" } <= 2, "only the new records were read: ${app.requests.drop(requestsBefore)}")
    }

    @Test
    fun `the lag behind the device is stated while catching up`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(3_000) { http(app, it + 1) }
        val host = index(app).apply { pageSize = 100; catchUpBudgetPages = 5 }
        val page = host.list(NetworkQuery(limit = 1))
        assertEquals(2_500L, page.coverage.hostLagRecords, "500 of 3000 indexed after the budget")
        repeat(10) { host.list(NetworkQuery(limit = 1)) }
        assertEquals(null, host.list(NetworkQuery(limit = 1)).coverage.hostLagRecords, "no lag once caught up")
    }

    @Test
    fun `without storage on the device, the device answers itself, as before`() = runBlocking<Unit> {
        val app = FakeJournalApp(persisted = false).also(::corpus)
        val host = index(app)
        assertEquals(app.deviceAnswer(NetworkQuery()), host.list(NetworkQuery()))
        assertEquals(1, app.listCalls)
    }

    @Test
    fun `the query line searches the host copy, bodies included (040)`() = runBlocking<Unit> {
        val app = FakeJournalApp().also(::corpus)
        val host = index(app)
        val page = host.list(NetworkQuery(q = "run:current \"item 10\"", limit = 50))
        assertEquals(setOf("r1-1:11"), page.hits!!.keys)
        // Feature 041: words leave only the matches; allRows keeps the rest, marked.
        assertEquals(listOf("r1-1:11"), page.entries.map { it.id })
        assertTrue(host.list(NetworkQuery(q = "run:current \"item 10\"", limit = 50, allRows = true)).entries.size > 1)
        val found = host.search(NetworkQuery(q = "item10 -status:4xx"))
        assertEquals(listOf("r1-1:11"), found.entries.map { it.id })
        assertEquals(io.yamsergey.dta.protocol.network.SearchPlace.RESPONSE_BODY, found.entries.single().hits.single().place)
    }

    @Test
    fun `without storage, search and host filters are said to be left out (040)`() = runBlocking<Unit> {
        val app = FakeJournalApp(persisted = false).also(::corpus)
        val host = index(app)
        val page = host.list(NetworkQuery(q = "status:4xx host:api cart"))
        assertEquals(app.deviceAnswer(NetworkQuery(filter = NetworkFilter(status = "4xx"))).entries, page.entries)
        assertTrue(page.degraded!!.any { it.reason == io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_SEARCH_UNAVAILABLE })
        assertEquals(null, host.list(NetworkQuery(q = "status:4xx")).degraded?.filter { it.reason == io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_SEARCH_UNAVAILABLE }?.ifEmpty { null })
        val s = host.search(NetworkQuery(q = "cart"))
        assertEquals(0, s.total)
        assertTrue(s.degraded!!.isNotEmpty())
    }

    @Test
    fun `a filtered list over 100k entries answers in under 200 ms (SC-003)`() = runBlocking<Unit> {
        val app = FakeJournalApp()
        repeat(100_000) { http(app, it + 1, url = "https://api/item/$it", status = if (it % 10 == 0) 404 else 200) }
        val host = index(app).apply { pageSize = 5_000; catchUpBudgetPages = 1_000 }
        host.list(NetworkQuery(limit = 1))
        repeat(3) { host.list(NetworkQuery(filter = NetworkFilter(status = "4xx", url = "item/9"))) } // warm
        val started = System.nanoTime()
        val page = host.list(NetworkQuery(filter = NetworkFilter(status = "4xx", url = "item/9")))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(page.total > 0)
        assertTrue(ms < 200, "took $ms ms")
    }
}
