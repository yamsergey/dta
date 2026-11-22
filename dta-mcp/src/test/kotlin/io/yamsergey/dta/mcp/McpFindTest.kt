package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.network.EntryHits
import io.yamsergey.dta.protocol.network.FindEnd
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import io.yamsergey.dta.protocol.network.SearchStoppedException
import io.yamsergey.dta.protocol.network.find
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 071 (FR-003, FR-006, FR-008): network_search streams on the daemon, reports progress, and stops on the agent's cancel. */
class McpFindTest {
    @Test
    fun `a cancel names its call by JSON-RPC id, also when it comes before the call starts`() {
        val cancels = McpCancels()
        val args = JsonObject(mapOf("q" to JsonPrimitive("token")))
        cancels.observe("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"network_search","arguments":{"q":"token"}}}""")
        cancels.observe("""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"network_search","arguments":{"q":"token"}}}""")
        val first = Job()
        val second = Job()
        cancels.claim("network_search", args, first)
        cancels.observe("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":8}}""")
        cancels.claim("network_search", args, second)
        assertTrue(second.isCancelled, "cancelled before it started")
        assertFalse(first.isCancelled)
        cancels.observe("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""")
        assertTrue(first.isCancelled)
    }

    private class Stdio(daemon: TestDaemon) : AutoCloseable {
        private val toServer = PipedOutputStream()
        private val fromServer = PipedInputStream(1 shl 20)
        private val serverOut = PipedOutputStream(fromServer)
        private val serverIn = PipedInputStream(toServer)
        private val server = thread { McpStdioServer.run(serverIn, serverOut, connect = { daemon.connect(ClientKind.MCP) }, logDir = Files.createTempDirectory("dta-mcp").toFile(), redirectStd = false) }
        private val reader = fromServer.bufferedReader()
        val notes = mutableListOf<JsonObject>()

        fun send(json: String) { toServer.write((json + "\n").toByteArray()); toServer.flush() }

        fun receive(id: Int): JsonObject {
            while (true) {
                val message = DtaJson.parseToJsonElement(reader.readLine() ?: error("server closed")).jsonObject
                if (message["id"]?.jsonPrimitive?.content == "$id") return message
                if (message["method"] != null) notes += message
            }
        }

        init {
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
            receive(1)
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        }

        override fun close() { toServer.close(); server.join(5_000) }
    }

    @Test
    fun `network_search gathers the stream into the search page, with progress and its state`() {
        TestDaemon().use { daemon ->
            val hit = SearchHit("r1-1:2", SearchPlace.URL, "cart", 27, 31, io.yamsergey.dta.protocol.network.Fuzzy.Excerpt("…/v1/cart", 4, 8))
            daemon.ops.searchHits = listOf(EntryHits("r1-1:2", 1_700_000_001_000, listOf(hit)), EntryHits("r1-1:1", 1_700_000_000_000, emptyList()))
            Stdio(daemon).use { s ->
                s.send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"network_search","arguments":{"q":"cart","archive":true},"_meta":{"progressToken":"p1"}}}""")
                val result = s.receive(3)["result"]!!.jsonObject
                assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean(), result.toString())
                val selected = DtaJson.decodeFromString(Selected.serializer(), result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
                val page = selected.result.jsonObject
                assertEquals(listOf("r1-1:2", "r1-1:1"), page["entries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
                assertEquals("done", page["state"]!!.jsonPrimitive.content)
                assertEquals("true", page["scope"]!!.jsonObject["archive"]!!.jsonPrimitive.content)
                assertTrue(daemon.ops.finds.single().archive)
                assertTrue(s.notes.any { it["method"]!!.jsonPrimitive.content == "notifications/progress" }, "progress: ${s.notes}")
            }
        }
    }

    @Test
    fun `the agent's cancel stops a runaway search on the daemon`() {
        TestDaemon().use { daemon ->
            daemon.ops.findWalk = { _, _ ->
                { stop, _ ->
                    io.yamsergey.dta.protocol.network.BodyPattern.find(java.util.regex.Pattern.compile("(x+x+)+y"), "x".repeat(1 shl 20)) { if (stop.stopped()) throw SearchStoppedException() }
                    FindEnd(null, null)
                }
            }
            Stdio(daemon).use { s ->
                s.send("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"network_search","arguments":{"q":"body:/(x+x+)+y/"}}}""")
                val deadline = System.currentTimeMillis() + 5_000
                while (daemon.ops.finds.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                Thread.sleep(200)
                s.send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":5,"reason":"user"}}""")
                while (daemon.ops.findsStopped.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, daemon.ops.findsStopped.get(), "the daemon's walk was stopped")
            }
        }
    }
}
