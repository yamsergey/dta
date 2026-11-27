package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.VersionIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 073 A1: the daemon knows each agent client's name, kind, version and since; `daemon.clients` pushes changes. */
class ClientRegistryTest {
    private val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-clients").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations()
    private val agents = io.yamsergey.dta.daemon.AgentSettings.inMemory(true)
    private val stopped = AtomicReference<String?>()
    private val server: DaemonServer = DaemonServer(
        identity, state, OperationDispatcher(fake, Selector(fake::devices, fake::apps), agents = agents), fake, Selector(fake::devices, fake::apps),
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = { reason -> stopped.set(reason); server.stop(reason) },
    ).also { it.start() }

    @AfterTest
    fun tearDown() = server.stop(null)

    private fun connect(kind: ClientKind, name: String? = null) = runBlocking { DaemonClient.connect(server.port, state.token()!!, kind, identity, name = name) }

    private suspend fun clients(asker: DaemonClient): List<JsonObject> =
        asker.call("daemon.status").result.jsonObject["clients"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun sessions(vararg s: Triple<String, String?, Long>, server: String? = null) = buildJsonObject {
        server?.let { put("server", it) }
        put("sessions", buildJsonArray {
            s.forEach { (name, version, since) -> add(buildJsonObject { put("name", name); version?.let { put("version", it) }; put("since", since) }) }
        })
    }

    @Test
    fun `the CLI hello says dta CLI`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val row = clients(cli).single { it.str("kind") == "cli" }
        assertEquals("dta CLI", row.str("name"))
        assertEquals("0.1.0", row.str("version"))
        assertTrue((row["connectedAtMs"]!!.jsonPrimitive.content.toLong()) > 0)
        cli.close()
    }

    @Test
    fun `a stdio MCP client names itself with client name`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val mcp = connect(ClientKind.MCP)
        assertNull(clients(cli).single { it.str("kind") == "mcp" }.str("name"), "unnamed until it says")
        mcp.request("client.name", buildJsonObject { put("name", "claude-code"); put("version", "2.1.0") })
        val row = clients(cli).single { it.str("kind") == "mcp" }
        assertEquals("claude-code", row.str("name"))
        assertEquals("2.1.0", row.str("agentVersion"))
        cli.close(); mcp.close()
    }

    @Test
    fun `the HTTP server's sessions are listed one row each and replaced by the next report`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val http = connect(ClientKind.MCP_HTTP)
        assertTrue(clients(cli).none { it.str("kind") == "mcp-http" }, "a server with no session lists nothing")
        http.request("client.sessions", sessions(Triple("cursor", "0.45", 1_000L), Triple("codex", null, 2_000L), server = "http://127.0.0.1:12321/mcp"))
        val rows = clients(cli).filter { it.str("kind") == "mcp-http" }
        assertEquals(listOf("cursor", "codex"), rows.map { it.str("name") })
        assertEquals("0.45", rows[0].str("agentVersion"))
        assertEquals(1_000L, rows[0]["connectedAtMs"]!!.jsonPrimitive.content.toLong())
        assertEquals("http://127.0.0.1:12321/mcp", rows[0].str("server"))
        http.request("client.sessions", sessions(Triple("codex", null, 2_000L)))
        assertEquals(listOf("codex"), clients(cli).filter { it.str("kind") == "mcp-http" }.map { it.str("name") })
        http.close()
        assertTrue(clients(cli).none { it.str("kind") == "mcp-http" }, "its sessions end with its connection")
        cli.close()
    }

    @Test
    fun `status never carries the credential`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val text = cli.call("daemon.status").result.toString()
        assertFalse(state.token()!! in text)
        cli.close()
    }

    @Test
    fun `daemon clients pushes a connect and a disconnect within a second`() = runBlocking<Unit> {
        val watcher = connect(ClientKind.IDE)
        val seen = async { withTimeout(5_000) { watcher.clientEvents().take(4).toList() } }
        delay(300)
        val t0 = System.nanoTime()
        val mcp = connect(ClientKind.MCP, name = null)
        mcp.request("client.name", buildJsonObject { put("name", "claude-code") })
        mcp.close()
        val events = seen.await()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val names = events.drop(1).map { e -> e["clients"]!!.jsonArray.filter { it.jsonObject.str("kind") == "mcp" }.map { it.jsonObject.str("name") } }
        assertTrue(elapsedMs < 1_000, "took $elapsedMs ms")
        assertTrue(events[0].containsKey("selection"))
        assertTrue(listOf("claude-code") in names, names.toString())
        assertEquals(emptyList<String?>(), names.last(), "the disconnect is pushed")
        watcher.close()
    }

    @Test
    fun `the first answer of daemon clients lists who is connected now`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val first = withTimeout(5_000) { cli.clientEvents().first() }
        val rows = first["selection"]!!.jsonObject["result"]!!.jsonObject["clients"]!!.jsonArray
        assertEquals(listOf("dta CLI"), rows.map { it.jsonObject.str("name") })
        cli.close()
    }

    /** D-121 (user decision 2026-11-27): a change of the agents' redaction setting is pushed to every IDE client that watches it. */
    @Test
    fun `a redaction change made by one IDE client is pushed to another that watches it`() = runBlocking<Unit> {
        val settings = connect(ClientKind.IDE)
        val tab = connect(ClientKind.IDE)
        val seen = async { withTimeout(5_000) { tab.redactionEvents().take(3).toList() } }
        delay(300)
        settings.call("agents.redaction.set", buildJsonObject { put("redaction", false) })
        settings.call("agents.redaction.set", buildJsonObject { put("redaction", true) })
        val events = seen.await()
        assertEquals(true, events[0]["selection"]!!.jsonObject["result"]!!.jsonObject["redaction"]!!.jsonPrimitive.content.toBoolean(), "the first answer holds the setting")
        assertEquals(listOf(false, true), events.drop(1).map { it["redaction"]!!.jsonPrimitive.content.toBoolean() })
        settings.close(); tab.close()
    }

    @Test
    fun `only the plugin's connection watches the redaction setting`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val refused = runCatching { withTimeout(5_000) { cli.redactionEvents().first() } }
        assertTrue(refused.isFailure, "an agent's connection is refused")
        cli.close()
    }
}
