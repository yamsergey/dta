package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.RpcResult
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.store.InstallationInfo
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.JournalPageResult
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Feature 069 T001: large archives for tests and the benchmark, shaped like the channel fixture's
 * traffic: mostly small socket messages on a few connections, some HTTP calls with headers and
 * small bodies, a few runs.
 */
object ArchiveFixtures {
    const val DEVICE = "dev1"
    const val PACKAGE = "io.example"
    const val JOURNAL = "j1"

    fun entry(i: Int, random: Random, startMs: Long = 1_700_000_000_000L): NetworkEntry {
        val run = "r${1 + i / 250_000}-1"
        val id = "$run:$i"
        val time = startMs + i * 8L
        return when {
            i % 5_000 == 0 -> WsConnectionEntry(id, run, time, "okhttp3-ws", url = "wss://stream.example.test/v${i % 3}/ws", state = WsState.OPEN, openedAtMs = time,
                handshakeHeaders = listOf(Header("Sec-WebSocket-Protocol", "json")))
            i % 20 == 0 -> {
                val status = if (random.nextInt(10) == 0) 404 else 200
                val text = """{"items":[${(0 until random.nextInt(1, 6)).joinToString(",") { """{"id":$it,"name":"item-$i-$it"}""" }}]}"""
                HttpEntry(id, run, time, "okhttp3", method = if (i % 40 == 0) "POST" else "GET", url = "https://api.example.test/v1/items/${i % 997}?page=${i % 7}",
                    state = HttpState.COMPLETE, status = status, durationMs = random.nextLong(5, 900),
                    requestHeaders = listOf(Header("Accept", "application/json")), responseHeaders = listOf(Header("Content-Type", "application/json")),
                    responseBody = BodyInfo(BodyPart.RESPONSE, text.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = text.take(256)))
            }
            else -> {
                val connection = "${"r${1 + i / 250_000}-1"}:${(i / 5_000) * 5_000}"
                val text = """{"type": "tick", "symbol": "S${i % 50}", "price": ${random.nextInt(1, 99_999)}}"""
                WsMessageEntry(id, run, time, "okhttp3-ws", connectionId = connection, direction = if (i % 9 == 0) Direction.OUT else Direction.IN, opcode = MessageOpcode.TEXT,
                    payload = BodyInfo(BodyPart.MESSAGE, text.length.toLong(), completeness = Completeness.COMPLETE, preview = text))
            }
        }
    }

    /** Writes [count] entries into the open epoch of [store] as if they were archived from journal lines `0:1`…`0:count`. */
    fun fill(store: HostStore, count: Int, seed: Int = 1, batch: Int = 5_000): List<String> {
        val random = Random(seed)
        val epoch = store.liveEpoch(DEVICE, PACKAGE) ?: store.openEpoch(DeviceIdentity(DEVICE, "emulator-5554"), PACKAGE, InstallationInfo("1.4.0", 14, 100, "aa"), JOURNAL, 1)
        var last = ""
        (0 until count).chunked(batch).forEach { chunk ->
            val entries = chunk.map { entry(it, random) }
            store.putEntries(epoch.id, entries)
            // As the archive does (070 E13): a body its preview holds whole is in its record; nothing else is written.
            last = entries.last().id
        }
        store.setCursor(DEVICE, PACKAGE, JOURNAL, "0:$count", last)
        return listOf(epoch.id)
    }

    /** The record on the journal's last line read: the one the archive holds for its cursor's last id (D-031: records are compared whole). */
    fun lastRecord(store: HostStore): NetworkEntry =
        store.entry(store.liveEpoch(DEVICE, PACKAGE)!!.id, store.cursor(DEVICE, PACKAGE)!!.lastId!!)!!

    /** An app whose journal this computer has read through line [lines]: nothing new to read. */
    class ReadThrough(private val lines: Int, private val last: NetworkEntry) : FakeJournalApp(JOURNAL) {
        init {
            installation = io.yamsergey.dta.protocol.rpc.AppInstallation("1.4.0", 14, 1, 100, "aa")
            entriesOverride = lines
        }

        override suspend fun requestFull(method: String, params: JsonObject?, timeout: Duration?): RpcResult {
            if (method != "network_journal") return super.requestFull(method, params, timeout)
            val after = params?.get("after")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val page = if (after == "0:${lines - 1}") listOf(last) else emptyList()
            return RpcResult(DtaJson.encodeToJsonElement(JournalPageResult.serializer(), JournalPageResult(JOURNAL, page, "0:$lines", true)))
        }
    }
}
