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
        assertEquals(Catalog.all.map { it.toolName }.toSet() - "daemon_stop" - "network_watch" - "apps_watch" - "timeline_watch" - "layout_watch", tools.tools.map { it.toolName }.toSet())
        assertEquals(Catalog["network.list"].inputSchema(), tools.byTool("network_list")!!.inputSchema())
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
        val text = ResultBound(4_000).apply("network.list", Selected(null, buildJsonObject { put("entries", entries); put("total", 200) }))
        assertTrue(text.toByteArray().size <= 4_000, "${text.length}")
        val doc = DtaJson.parseToJsonElement(text).jsonObject
        val kept = doc["result"]!!.jsonObject["entries"]!!.jsonArray.size
        assertTrue(kept in 1..199)
        assertTrue("${200 - kept} of 200 entries" in doc["truncated"]!!.jsonObject["omitted"]!!.jsonPrimitive.content)
        assertTrue("before" in doc["truncated"]!!.jsonObject["how"]!!.jsonPrimitive.content)
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
            send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"network_list","arguments":{}}}""")
            val result = receive(3)["result"]!!.jsonObject
            assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
            val selected = DtaJson.decodeFromString(Selected.serializer(), result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("com.example.shop", selected.selection!!.app)
            toServer.close()
            server.join(5_000)
        }
    }
}
