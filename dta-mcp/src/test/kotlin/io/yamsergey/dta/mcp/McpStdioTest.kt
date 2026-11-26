package io.yamsergey.dta.mcp

import io.yamsergey.dta.testing.TestDaemon
import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpStdioTest {

    // --- Tool catalog (T036)

    @Test
    fun `tools are the catalog minus stop and the watches, with the catalog's schema`() {
        val tools = McpTools(call = { _, _ -> error("unused") })
        assertEquals(Catalog.all.map { it.toolName }.toSet() - "daemon_stop" - "network_watch" - "apps_watch" - "timeline_watch" - "layout_watch" - "agents_redaction_set", tools.tools.map { it.toolName }.toSet())
        assertEquals(Catalog.agentTools.single { it.name == "network.list" }.inputSchema(), tools.byTool("network_list")!!.inputSchema())
    }

    // --- Bounds (T037)

    @Test
    fun `small results pass through unchanged`() {
        val selected = Selected(null, buildJsonObject { put("clearedEntries", 3) })
        assertEquals(DtaJson.encodeToString(Selected.serializer(), selected), ResultBound(1000).apply("network.clear", selected))
    }

    @Test
    fun `lists are cut at item boundaries and say so`() {
        val entries = buildJsonArray { repeat(200) { i -> add(buildJsonObject { put("id", "r1-1:$i"); put("url", "https://api.example.com/" + "x".repeat(100)) }) } }
        val text = ResultBound(4_000).apply("network.list", Selected(null, buildJsonObject { put("entries", entries) }))
        assertTrue(text.toByteArray().size <= 4_000, "${text.length}")
        val doc = DtaJson.parseToJsonElement(text).jsonObject
        val kept = doc["result"]!!.jsonObject["entries"]!!.jsonArray.size
        assertTrue(kept in 1..199)
        assertTrue("${200 - kept} of 200 entries" in doc["truncated"]!!.jsonObject["omitted"]!!.jsonPrimitive.content)
        assertTrue("before" in doc["truncated"]!!.jsonObject["how"]!!.jsonPrimitive.content)
    }

    /**
     * D-049 (NET-QRY-084, 071 FR-003): a network page cut to fit goes on where the cut is: its next is
     * the last row kept, so paging with before = next lists what the plugin lists, none skipped.
     */
    @Test
    fun `a cut network page goes on from the last row kept`() {
        val entries = buildJsonArray { repeat(200) { i -> add(buildJsonObject { put("id", "r1-1:${200 - i}"); put("url", "https://api.example.com/" + "x".repeat(100)) }) } }
        for (op in listOf("network.list", "network.search")) {
            val doc = DtaJson.parseToJsonElement(ResultBound(4_000).apply(op, Selected(null, buildJsonObject { put("entries", entries) }))).jsonObject
            val kept = doc["result"]!!.jsonObject["entries"]!!.jsonArray
            assertEquals(kept.last().jsonObject["id"]!!.jsonPrimitive.content, doc["result"]!!.jsonObject["next"]?.jsonPrimitive?.content, "$op: next is the last row kept")
        }
        // A page that fits keeps its own next (null: nothing more).
        val small = DtaJson.parseToJsonElement(ResultBound(100_000).apply("network.list", Selected(null, buildJsonObject { put("entries", entries) }))).jsonObject
        assertEquals(null, small["result"]!!.jsonObject["next"])
    }

    /**
     * D-064 (MCP-STUDIO-005): with archive: true a page marks each archived row (`archived`, by id), and
     * `hits`, `matches` and `fields` are by id too. The cut kept them whole: over a long-kept history the
     * marks alone passed the bound, so the cut went on to no rows at all and archive: true answered [].
     */
    @Test
    fun `a cut network page keeps only the marks of the rows it keeps`() {
        val ids = (1..800).map { "r1-1:${800 - it}" }
        val entries = buildJsonArray { ids.forEach { id -> add(buildJsonObject { put("id", id); put("url", "https://api.example.com/" + "x".repeat(40)) }) } }
        fun byId(value: () -> kotlinx.serialization.json.JsonElement) = buildJsonObject { ids.forEach { put(it, value()) } }
        val page = buildJsonObject {
            put("entries", entries)
            put("archived", byId { buildJsonObject { put("epoch", "ba415517-69df-473d-aeb4-79d8aa9629e4"); put("reason", "REINSTALLED"); put("closedMs", 1795413650000) } })
            put("hits", byId { buildJsonArray { add(buildJsonObject { put("where", "url"); put("text", "x") }) } })
            put("matches", byId { buildJsonArray { add(buildJsonObject { put("part", "response") }) } })
            put("fields", byId { buildJsonArray { add(JsonPrimitive("$.a")) } })
        }
        for (op in listOf("network.list", "network.search")) {
            val text = ResultBound(65_536).apply(op, Selected(null, page))
            assertTrue(text.toByteArray().size <= 65_536, "$op: ${text.length}")
            val result = DtaJson.parseToJsonElement(text).jsonObject["result"]!!.jsonObject
            val kept = result["entries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue(kept.size >= 100, "$op: the rows fill the bound, not the marks of rows cut away: ${kept.size}")
            assertEquals(ids.take(kept.size), kept, "$op: the newest rows, in order")
            for (key in listOf("archived", "hits", "matches", "fields")) {
                assertEquals(kept.toSet(), result[key]!!.jsonObject.keys, "$op: $key holds the kept rows' only")
            }
            assertEquals(kept.last(), result["next"]?.jsonPrimitive?.content, "$op: goes on from the last row kept")
        }
    }

    @Test
    fun `trees are cut at a depth and say how to get the rest`() {
        fun node(depth: Int): JsonObject = buildJsonObject {
            put("kind", "view"); put("className", "android.widget.FrameLayout"); put("text", "y".repeat(50))
            put("children", if (depth == 0) JsonArray(emptyList()) else buildJsonArray { repeat(3) { add(node(depth - 1)) } })
        }
        val layout = buildJsonObject { put("windows", buildJsonArray { add(buildJsonObject { put("type", "application"); put("root", node(7)) }) }) }
        val text = ResultBound(20_000).apply("layout.tree", Selected(null, layout))
        assertTrue(text.toByteArray().size <= 20_000)
        val truncated = DtaJson.parseToJsonElement(text).jsonObject["truncated"]!!.jsonObject
        assertTrue("childrenOmitted" in truncated["omitted"]!!.jsonPrimitive.content)
        assertTrue("rootId" in truncated["how"]!!.jsonPrimitive.content)
    }

    // --- Errors (T038)

    @Test
    fun `errors name the problem and the next step`() = runBlocking<Unit> {
        fun failing(code: Int, message: String, data: JsonObject? = null) = McpTools(call = { _, _ -> throw DaemonCallException(code, message, data) })
        val several = failing(ClientErrorCodes.SEVERAL_DEVICES, "Several devices", buildJsonObject { put("devices", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }) })
            .invoke("network_list", JsonObject(emptyMap()))
        assertTrue(several.isError && "Pass device: one of a, b." in several.text, several.text)
        val noApp = failing(ClientErrorCodes.NO_APP, "No DTA in front", buildJsonObject { put("appsWithDta", buildJsonArray { add(JsonPrimitive("com.example.shop")) }) })
            .invoke("network_list", JsonObject(emptyMap()))
        assertTrue("com.example.shop" in noApp.text, noApp.text)
        val invalid = failing(ErrorCodes.INVALID_PARAMS, "parameter 'part' must be one of …").invoke("network_body", JsonObject(emptyMap()))
        assertTrue("part (request|response|message|app-request|app-response|app-message)" in invalid.text && "id required" in invalid.text, invalid.text)
        val unusable = McpTools(call = { _, _ -> error("unused") }, unusable = { "The running DTA daemon is newer. Upgrade dta." }).invoke("devices_list", JsonObject(emptyMap()))
        assertTrue(unusable.isError && "Upgrade dta" in unusable.text)
    }

    // --- The scripting guide (D-018): served as a resource, from the skill's own file

    @Test
    fun `the instructions and network_script_set point at the scripting guide`() {
        assertTrue(McpGuides.SCRIPT_API_URI in McpTools.INSTRUCTIONS, McpTools.INSTRUCTIONS)
        val set = McpTools(call = { _, _ -> error("unused") }).byTool("network_script_set")!!.description
        assertTrue(McpGuides.SCRIPT_API_URI in set, set)
        assertEquals(scriptingGuide(), McpGuides.all.single { it.uri == McpGuides.SCRIPT_API_URI }.text)
    }

    // --- Stdio server against an in-process daemon (T039)

    @Test
    fun `stdio serves the tools and writes nothing but frames`() {
        TestDaemon().use { daemon ->
            val toServer = PipedOutputStream()
            val serverIn = PipedInputStream(toServer)
            val fromServer = PipedInputStream()
            val serverOut = PipedOutputStream(fromServer)
            val logDir = Files.createTempDirectory("dta-mcp").toFile()
            val server = thread { McpStdioServer.run(serverIn, serverOut, connect = { daemon.connect(ClientKind.MCP) }, logDir = logDir, redirectStd = false) }
            val reader = fromServer.bufferedReader()
            fun send(json: String) { toServer.write((json + "\n").toByteArray()); toServer.flush() }
            fun receive(id: Int): JsonObject {
                while (true) {
                    val line = reader.readLine() ?: error("server closed")
                    val message = DtaJson.parseToJsonElement(line).jsonObject
                    if (message["id"]?.jsonPrimitive?.content == "$id") return message
                }
            }
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
            assertEquals("dta", receive(1)["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals(Catalog.agentTools.size + 1, receive(2)["result"]!!.jsonObject["tools"]!!.jsonArray.size)
            send("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""")
            val resources = receive(4)["result"]!!.jsonObject["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
            assertTrue(McpGuides.SCRIPT_API_URI in resources, "resources: $resources")
            send("""{"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"${McpGuides.SCRIPT_API_URI}"}}""")
            val contents = receive(5)["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject
            assertEquals(scriptingGuide(), contents["text"]!!.jsonPrimitive.content)
            assertEquals("text/markdown", contents["mimeType"]!!.jsonPrimitive.content)
            send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"network_list","arguments":{}}}""")
            val result = receive(3)["result"]!!.jsonObject
            assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
            val selected = DtaJson.decodeFromString(Selected.serializer(), result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("com.example.shop", selected.selection!!.app)
            toServer.close()
            server.join(5_000)
        }
    }

    // --- Feature 073 A2: the agent's name reaches the daemon

    private fun namesSeenBy(daemon: TestDaemon, kind: String, wait: Long = 3_000): List<Pair<String?, String?>> = runBlocking {
        val cli = daemon.connect(ClientKind.CLI).client
        try {
            val until = System.currentTimeMillis() + wait
            var rows: List<Pair<String?, String?>>
            do {
                rows = cli.call("daemon.status").result.jsonObject["clients"]!!.jsonArray.map { it.jsonObject }
                    .filter { it["kind"]!!.jsonPrimitive.content == kind }
                    .map { (it["name"] as? JsonPrimitive)?.content to (it["agentVersion"] as? JsonPrimitive)?.content }
                if (rows.any { it.first != null }) break
                Thread.sleep(50)
            } while (System.currentTimeMillis() < until)
            rows
        } finally { cli.close() }
    }

    private fun initializeAndWatch(initialize: String): List<Pair<String?, String?>> = TestDaemon().use { daemon ->
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer)
        val fromServer = PipedInputStream()
        val serverOut = PipedOutputStream(fromServer)
        val logDir = Files.createTempDirectory("dta-mcp").toFile()
        val server = thread { McpStdioServer.run(serverIn, serverOut, connect = { daemon.connect(ClientKind.MCP) }, logDir = logDir, redirectStd = false) }
        toServer.write((initialize + "\n").toByteArray()); toServer.flush()
        val seen = namesSeenBy(daemon, "mcp")
        toServer.close()
        server.join(5_000)
        seen
    }

    @Test
    fun `stdio tells the daemon the agent's name and version after initialize`() {
        val seen = initializeAndWatch("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1.0"}}}""")
        assertEquals(listOf("claude-code" to "2.1.0"), seen)
    }

    @Test
    fun `a missing clientInfo is an unnamed client`() {
        val seen = initializeAndWatch("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
        assertEquals(listOf("unnamed client" to null), seen)
    }

    @Test
    fun `naming reads only initialize`() {
        assertEquals(null, ClientNaming.fromInitialize("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
        assertEquals(null, ClientNaming.fromInitialize("not json"))
        assertEquals("a", ClientNaming.fromInitialize("""{"method":"initialize","params":{"clientInfo":{"name":"a"}}}""")!!.name)
        assertEquals("unnamed client", ClientNaming.fromInitialize("""{"method":"initialize","params":{"clientInfo":{"name":"  "}}}""")!!.name)
    }
}

/** The skill's scripting guide, read from the repository: what the server serves must be this file. */
internal fun scriptingGuide(): String =
    generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { java.io.File(it, "plugins/dta/skills/dta/scripting.md") }
        .first { it.isFile }
        .readText()
