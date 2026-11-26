package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.AgentSettings
import io.yamsergey.dta.daemon.network.FakeJournalApp
import io.yamsergey.dta.daemon.network.HostIndex
import io.yamsergey.dta.daemon.network.NetworkService
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Redactor
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.ops.ClientKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 072 D1 (SC-001..003): over 1,000 random histories with secrets planted in every masked place (URL query, Authorization,
 * Cookie, Set-Cookie, form, JSON, nested JSON, a failure message, a socket's handshake and messages), no planted value is
 * in any answer an agent kind reads or in its default export; a mask's fingerprint is that value's, equal values share it
 * and different ones do not — in the live history, after the daemon restarts (a new key read from the same home) and in an
 * archived epoch; and `redacted:#fp` finds exactly the entries the value was planted in.
 */
class RedactionPropertyTest {
    @TempDir lateinit var dir: File
    private val stores = mutableListOf<HostStore>()

    @AfterEach fun close() { Redactor.use(null); stores.forEach { it.close() } }

    /** The real network service behind the library operations; the rest as the fake has it. */
    private class RealOps(val service: NetworkService) : FakeOperations() {
        override suspend fun network(serial: String, pkg: String, query: NetworkQuery) = service.list(query)
        override suspend fun networkSearch(serial: String, pkg: String, query: NetworkQuery) = service.search(query)
        override suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean) = service.entry(id, redact)
        override suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean) = service.body(id, part, output, redact)
    }

    private class History(val entries: List<NetworkEntry>, val bodies: Map<Pair<String, BodyPart>, String>, val planted: Map<String, Set<Int>>)

    private fun history(r: Random, run: Int, secrets: List<String>): History {
        val entries = ArrayList<NetworkEntry>()
        val bodies = HashMap<Pair<String, BodyPart>, String>()
        val planted = HashMap<String, MutableSet<Int>>()
        fun pick(id: String): Pair<Int, String> = r.nextInt(secrets.size).let { i -> planted.getOrPut(id) { mutableSetOf() } += i; i to secrets[i] }
        fun body(id: String, part: BodyPart, type: String, text: String) = BodyInfo(part, text.length.toLong(), contentType = type, completeness = Completeness.COMPLETE).also { bodies[id to part] = text }
        val socket = WsConnectionEntry("r1-1:s", "r1-1", 1_000, "okhttp3-ws", url = "wss://push.example.test/live", state = WsState.OPEN,
            handshakeHeaders = listOf(Header("Authorization", "Bearer ${pick("r1-1:s").second}")))
        entries += socket
        for (n in 1..r.nextInt(3, 7)) {
            val id = "r1-1:$n"
            val t = 2_000L + n
            entries += when (r.nextInt(5)) {
                0 -> HttpEntry(id, "r1-1", t, "okhttp3", method = "GET", url = "https://api.example.test/v1/a?access_token=${pick(id).second}&page=2", state = HttpState.COMPLETE, status = 200,
                    requestHeaders = listOf(Header("Authorization", "Bearer ${pick(id).second}"), Header("Cookie", "session=${pick(id).second}; theme=dark")),
                    responseBody = body(id, BodyPart.RESPONSE, "application/json", """{"data":[{"refresh_token":"${pick(id).second}"}],"ok":true}"""))
                1 -> HttpEntry(id, "r1-1", t, "okhttp3", method = "POST", url = "https://api.example.test/v1/login", state = HttpState.COMPLETE, status = 200,
                    requestBody = body(id, BodyPart.REQUEST, "application/x-www-form-urlencoded", "username=ana&password=${pick(id).second}"),
                    responseHeaders = listOf(Header("Set-Cookie", "id=${pick(id).second}; Path=/; HttpOnly")))
                2 -> HttpEntry(id, "r1-1", t, "okhttp3", method = "GET", url = "https://api.example.test/v1/x", state = HttpState.FAILED, failure = "java.io.IOException: token=${pick(id).second} refused")
                3 -> HttpEntry(id, "r1-1", t, "okhttp3", method = "POST", url = "https://api.example.test/v1/n", state = HttpState.COMPLETE, status = 200,
                    requestBody = body(id, BodyPart.REQUEST, "application/json", """{"payload":"{\"api_key\":\"${pick(id).second}\"}","name":"ok"}"""))
                else -> WsMessageEntry(id, "r1-1", t, "okhttp3-ws", connectionId = socket.id, direction = if (r.nextBoolean()) Direction.IN else Direction.OUT, opcode = MessageOpcode.TEXT,
                    payload = body(id, BodyPart.MESSAGE, "application/json", """{"event":{"user":"ana"},"token":"${pick(id).second}","seq":$n}"""))
            }
        }
        return History(entries, bodies, planted)
    }

    private val kinds = listOf(ClientKind.CLI, ClientKind.MCP, ClientKind.MCP_HTTP)

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> listOf(e.content)
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }

    /** Every masked location in [answer], as (length, fingerprint). */
    private fun masks(answer: JsonElement): List<Pair<Int, String>> = when (answer) {
        is JsonArray -> answer.flatMap(::masks)
        is JsonObject -> (answer["masked"] as? JsonArray)?.mapNotNull { m -> (m as? JsonObject)?.let { it["length"]?.jsonPrimitive?.content?.toInt()!! to it["fingerprint"]!!.jsonPrimitive.content } }.orEmpty() +
            answer.filterKeys { it != "masked" }.values.flatMap(::masks)
        else -> emptyList()
    }

    @Test
    fun `1,000 histories - nothing planted reaches an agent, fingerprints say which value, search finds exactly where`() {
        var checked = 0
        repeat(1_000) { run ->
            val r = Random(run)
            // Distinct lengths: a mask's length says which planted value it is, so its fingerprint can be checked against that value's.
            val secrets = List(3) { i -> "S$run-${i}k".padEnd(12 + 3 * i, 'q') }
            // Every cookie value is masked (decision 2026-11-26): `theme=dark` too.
            val byLength = secrets.associateBy { it.length } + (4 to "dark")
            val h = history(r, run, secrets)
            val app = FakeJournalApp()
            h.entries.forEach { app.record(it) }
            h.bodies.forEach { (k, v) -> app.bodies[k] = v.toByteArray() }
            val home = File(dir, "home$run").also { it.mkdirs() }
            val store = HostStore(File(dir, "store$run").also { it.mkdirs() }).also { stores += it }
            val host = HostIndex(store, DeviceIdentity("dev1", "emulator-5554"), "io.example") { app }
            val service = NetworkService(host = host) { app }
            val ops = RealOps(service)
            ops.timelineEvents = listOf(io.yamsergey.dta.protocol.timeline.TimelineEvent("7:1", 1, 1, 1, "network", "network/failed", title = "failed: token=${secrets[0]}"))

            fun phase(agents: AgentSettings, scope: Map<String, String>) {
                Redactor.use(agents.key)
                val dispatcher = OperationDispatcher(ops, Selector(ops::devices, ops::apps), agents = agents)
                fun fp(i: Int) = agents.key.of(secrets[i])
                for (kind in kinds) {
                    fun call(op: String, params: Map<String, Any> = emptyMap()) = runBlocking {
                        dispatcher.dispatch(op, buildJsonObject { ((if (op in setOf("network.list", "network.search", "network.export")) scope else emptyMap()) + params).forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) } }, kind).result
                    }
                    val answers = ArrayList<JsonElement>()
                    val list = call("network.list")
                    answers += list
                    for (e in list.jsonObject["entries"]!!.jsonArray) {
                        val id = e.jsonObject["id"]!!.jsonPrimitive.content
                        answers += call("network.entry", mapOf("id" to id))
                        for (part in listOf("request", "response", "message")) if (h.bodies.containsKey(id to BodyPart.valueOf(part.uppercase()))) answers += call("network.body", mapOf("id" to id, "part" to part))
                    }
                    answers += call("network.search", mapOf("q" to "example"))
                    answers += call("timeline.events")
                    val out = File(dir, "x$run-$kind.har")
                    call("network.export", mapOf("output" to out.path))
                    val har = out.readText()
                    for (s in secrets) {
                        assertFalse(answers.any { a -> strings(a).any { s in it } }, "run $run $kind: $s is in an answer")
                        assertFalse(s in har, "run $run $kind: $s is in the export")
                    }
                    // A mask's fingerprint is the fingerprint of the value its length says.
                    for ((length, fingerprint) in answers.flatMap(::masks).filter { it.second.isNotEmpty() }) {
                        val secret = byLength[length] ?: error("run $run $kind: a mask of length $length is none of the planted values")
                        assertEquals(agents.key.of(secret), fingerprint, "run $run $kind: fingerprint of $secret")
                        checked++
                    }
                    // Search by fingerprint finds exactly the entries the value was planted in.
                    for (i in secrets.indices) {
                        val found = call("network.search", mapOf("q" to "redacted:#${fp(i)}", "limit" to 1000)).jsonObject["entries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
                        assertEquals(h.planted.filterValues { i in it }.keys, found, "run $run $kind: redacted:#${fp(i)} (${secrets[i]}) in ${h.entries.filter { it.id in h.planted.filterValues { v -> i in v }.keys - found }}")
                    }
                }
            }

            // The history is read once (the host index syncs the app's journal), and the bodies copied, as the daemon does in the background.
            runBlocking { service.list(NetworkQuery()); h.bodies.keys.forEach { (id, part) -> host.copyBody(id, part) } }
            val first = AgentSettings(home)
            phase(first, emptyMap())
            // The daemon restarts: a new setting object over the same home reads the same key, so the same fingerprints.
            val restarted = AgentSettings(home)
            assertEquals(first.key.of(secrets[0]), restarted.key.of(secrets[0]), "run $run: the key survives a restart")
            phase(restarted, emptyMap())
            // The history archived: an epoch the device no longer has.
            runBlocking { service.clear(); service.list(NetworkQuery()) }
            phase(restarted, mapOf("archiveScope" to "all"))
        }
        assertTrue(checked > 3_000, "masks checked: $checked")
    }
}
