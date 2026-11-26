package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.EntrySource
import io.yamsergey.dta.protocol.network.EntrySourceProvider
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.FingerprintKey
import io.yamsergey.dta.protocol.network.Redactor
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.IndexText
import io.yamsergey.dta.protocol.network.LinkDelivery
import io.yamsergey.dta.protocol.network.LinkEntry
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Need
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.SearchPage
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 072 B1 (R7, FR-011, FR-012): `redacted:#fp` finds exactly the entries with a masked place of that fingerprint —
 * in the app's memory history and in the archive, with the word index written, partly written or not at all. The oracle is
 * the redactor itself: the fingerprints of what it masks in each entry.
 */
class FingerprintSearchEquivalenceTest {
    @TempDir
    lateinit var dir: File

    private lateinit var store: HostStore

    @BeforeEach fun key() = Redactor.use(FingerprintKey(ByteArray(32) { (it * 7).toByte() }))
    @AfterEach fun close() { Redactor.use(null); if (::store.isInitialized) store.close() }

    private val words = listOf("tick", "heartbeat", "price", "token", "expired", "payment", "orders", "item-12", "lineitem123", "boom", "cart", "AbCd-9x")
    private val hosts = listOf("api.example.test", "telemetry.example.test", "push.example.test")

    private fun fp(word: String) = FingerprintKey(ByteArray(32) { (it * 7).toByte() }).of(word)

    private fun history(r: Random, prefix: String, size: Int, startMs: Long, bodies: MutableMap<Pair<String, BodyPart>, String>): List<NetworkEntry> {
        val out = ArrayList<NetworkEntry>()
        val sockets = (0 until r.nextInt(1, 3)).map { i ->
            WsConnectionEntry("$prefix:s$i", prefix, startMs + i, "okhttp3-ws", url = "wss://${hosts.random(r)}/live/${words.random(r)}", state = WsState.OPEN,
                handshakeHeaders = listOf(Header("Authorization", "Bearer ${words.random(r)}")))
        }
        out += sockets
        for (n in 1..size) {
            val id = "$prefix:$n"
            val body = { part: BodyPart ->
                // A secret in a JSON field of the body, as often as not.
                val t = if (r.nextInt(3) == 0) "{\"password\":\"${words.random(r)}\",\"v\":\"x\"}" else "{\"type\":\"${words.random(r)}\",\"v\":\"x\"}"
                val copied = r.nextInt(4) != 0
                if (copied) bodies[id to part] = t
                BodyInfo(part, t.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = if (copied) null else t)
            }
            out += when (r.nextInt(7)) {
                0 -> LinkEntry(id, prefix, startMs + n * 10, uri = "https://${hosts.random(r)}/open/${words.random(r)}?token=${words.random(r)}", activity = "Main", delivery = LinkDelivery.COLD_START)
                1, 2, 3 -> HttpEntry(id, prefix, startMs + n * 10, "okhttp3", method = listOf("GET", "POST").random(r), url = "https://${hosts.random(r)}/v1/${words.random(r)}/$n?token=${words.random(r)}",
                    state = HttpState.COMPLETE, status = listOf(200, 404, 500, null).random(r), responseBody = if (r.nextInt(4) == 0) null else body(BodyPart.RESPONSE),
                    requestHeaders = if (r.nextBoolean()) listOf(Header("Authorization", "Bearer ${words.random(r)}"), Header("Cookie", "session=${words.random(r)}")) else null)
                else -> WsMessageEntry(id, prefix, startMs + n * 10, "okhttp3-ws", connectionId = sockets.random(r).id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT,
                    opcode = MessageOpcode.TEXT, payload = body(BodyPart.MESSAGE))
            }
        }
        return out
    }

    private fun context() = HistoryContext("r0", Coverage(searched = 0, stored = 0, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE))

    private fun whole(epochs: () -> List<String>) = object : EntrySourceProvider {
        override fun open(filter: NetworkFilter?): EntrySource = store.readSource(epochs(), filter)
        override fun open(filter: NetworkFilter?, need: Need): EntrySource = store.readSource(epochs(), filter)
    }

    /** What the redactor masks in [e], by fingerprint (the whole token's and a JWT's parts'). */
    private fun fingerprintsOf(e: NetworkEntry, bodies: Map<Pair<String, BodyPart>, String>): Set<String> =
        (Redactor.entry(e).second.masked + bodies.filterKeys { it.first == e.id }.values.flatMap { Redactor.body(it, "application/json", "").masked }).flatMap { m ->
            listOf(m.fingerprint) + listOfNotNull(m.jwt?.sub, m.jwt?.signature) + m.jwt?.claims?.values.orEmpty()
        }.toSet()

    private fun ids(page: NetworkPage) = page.entries.map { it.id }.toSet()

    @Test
    fun `a fingerprint finds the entries the redactor masks it in, memory and archive, index or not (1,000 runs)`() {
        store = HostStore(dir)
        var found = 0
        repeat(1_000) { run ->
            val r = Random(run)
            store.readPage = if (r.nextBoolean()) r.nextInt(1, 8) else 500
            val bodies = HashMap<Pair<String, BodyPart>, String>()
            val bytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes? = { e, p -> bodies[e.id to p]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } }
            val all = ArrayList<NetworkEntry>()
            val epochs = (0 until r.nextInt(1, 3)).map { k ->
                val entries = history(r, "r$run-$k", r.nextInt(5, 40), 1_000L * k, bodies)
                all += entries
                val epoch = store.openEpoch(DeviceIdentity("dev$run"), "io.example", InstallationInfo("1.$k", k.toLong(), k.toLong(), "aa"), "j$run-$k", 1_000L * k)
                entries.chunked(r.nextInt(1, 20)).forEach { store.putEntries(epoch.id, it) }
                epoch.id
            }
            val pending = store.pendingWords(epochs, 100_000)
            store.putWords(pending.filter { r.nextInt(3) != 0 }.map { p -> IndexText.of(p.entry, bytes).let { WordWrite(p.rowid, p.mark, it.text, it.cut) } })
            val memory = NetworkHistoryView({ all }, { id -> all.firstOrNull { it.id == id } }, ::context, bodyBytes = bytes)
            val indexed = NetworkHistoryView.over(store.sources { epochs }, ::context, bytes)
            val read = NetworkHistoryView.over(whole { epochs }, ::context, bytes)
            repeat(3) {
                val word = words.random(r)
                val q = "redacted:#${fp(word)}"
                val expected = all.filter { it !is LinkEntry && fp(word) in fingerprintsOf(it, bodies) }.map { it.id }.toSet()
                for (redact in listOf(true, false)) {
                    val query = NetworkQuery(q = q, limit = 1000, order = Order.OLDEST_FIRST, redact = redact)
                    assertEquals(expected, ids(memory.list(query)), "run $run memory redact=$redact $q")
                    assertEquals(expected, ids(read.list(query)), "run $run archive read whole redact=$redact $q")
                    assertEquals(expected, ids(indexed.list(query)), "run $run archive through its index redact=$redact $q")
                    assertEquals(expected, memory.search(query).entries.map { it.id }.toSet(), "run $run search memory redact=$redact $q")
                    assertEquals(expected, indexed.search(query).entries.map { it.id }.toSet(), "run $run search archive redact=$redact $q")
                }
                found += expected.size
                // A fingerprint of nothing finds nothing; the answer says what it searched.
                val none = indexed.search(NetworkQuery(q = "redacted:#000000000000", limit = 50, redact = true))
                assertEquals(emptyList(), none.entries.map { it.id })
            }
        }
        assertTrue(found > 1_000, "entries found: $found")
    }

    @Test
    fun `a hit says where and the answer says it searched masked places only`() {
        val entry = HttpEntry("e1", "r", 1, "okhttp3", method = "GET", url = "https://h/p?access_token=SECRET-VALUE&page=2", state = HttpState.COMPLETE, status = 200,
            requestHeaders = listOf(Header("Authorization", "Bearer SECRET-VALUE")))
        val view = NetworkHistoryView({ listOf(entry) }, { id -> entry.takeIf { it.id == id } }, ::context, bodyBytes = { _, _ -> null })
        val q = "redacted:#${fp("SECRET-VALUE")}"
        val page = view.search(NetworkQuery(q = q, redact = true))
        assertEquals(listOf("e1"), page.entries.map { it.id })
        val hits = page.entries.single().hits
        assertEquals(setOf(io.yamsergey.dta.protocol.network.SearchPlace.URL, io.yamsergey.dta.protocol.network.SearchPlace.REQUEST_HEADER), hits.map { it.place }.toSet())
        val shown = Redactor.url(entry.url, "").first
        val urlHit = hits.single { it.place == io.yamsergey.dta.protocol.network.SearchPlace.URL }
        assertEquals("#${fp("SECRET-VALUE")}", shown.substring(urlHit.start, urlHit.end), "the span is in the masked text the answer shows")
        assertTrue(page.notSearched.orEmpty().any { "searched masked places only" in it }, "${page.notSearched}")
        assertEquals(emptyList(), view.search(NetworkQuery(q = "redacted:#${fp("page")}", redact = true)).entries)
    }

    @Test
    fun `the term is checked - twelve hex characters, with or without the hash`() {
        val p = io.yamsergey.dta.protocol.network.QueryLine.parse("redacted:#${fp("x")} redacted:zz redacted:${fp("y")}")
        assertEquals(listOf("#${fp("x")}", "#${fp("y")}"), p.terms.map { it.text })
        assertTrue(p.terms.all { it.redacted })
        assertTrue(p.problems.single().message.contains("redacted:"), p.problems.toString())
        assertEquals("redacted:#${fp("x")}", io.yamsergey.dta.protocol.network.QueryLine.render(io.yamsergey.dta.protocol.network.QueryLine.parse("redacted:#${fp("x")}")))
    }
}
