package io.yamsergey.dta.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The agent server over HTTP (feature 009 US4, contract agent-server.md), against an in-process daemon. */
class McpHttpServerTest {

    private val daemon = TestDaemon()
    private val credential = "test-credential-0123456789"
    private val started = mutableListOf<McpHttpServer>()

    @AfterTest
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        daemon.close()
    }

    private fun freePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun server(port: Int = freePort()) =
        McpHttpServer(port = port, credential = credential, connect = { daemon.connect(ClientKind.MCP_HTTP) }).also { started += it }

    private fun client(server: McpHttpServer, token: String? = credential): McpSyncClient {
        val transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:${server.port}")
            .endpoint("/mcp")
            .jsonMapper(JacksonMcpJsonMapper(JsonMapper.builder().build()))
            .requestBuilder(HttpRequest.newBuilder().apply { if (token != null) header("Authorization", "Bearer $token") })
            .build()
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build().also { it.initialize() }
    }

    private fun post(server: McpHttpServer, authorization: String?): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:${server.port}/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        connection.outputStream.use {
            it.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""".toByteArray())
        }
        val code = connection.responseCode
        val body = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to body
    }

    @Test
    fun `the tools are the stdio tools`() {
        val server = server().apply { start() }
        client(server).use { client ->
            assertEquals(Catalog.agentTools.map { it.toolName }.toSet() + AppRunTool.NAME, client.listTools().tools().map { it.name() }.toSet())
        }
    }

    @Test
    fun `a call returns the same text as stdio for the same request`() {
        val server = server().apply { start() }
        val stdioText = runBlocking {
            val connected = daemon.connect(ClientKind.MCP)
            try {
                McpTools(call = connected.client::call).invoke("network_list", JsonObject(emptyMap()))
            } finally {
                connected.client.close()
            }
        }
        client(server).use { client ->
            val result = client.callTool(McpSchema.CallToolRequest.builder().name("network_list").arguments(emptyMap<String, Any>()).build())
            assertFalse(result.isError() ?: false)
            assertEquals(stdioText.text, (result.content().single() as McpSchema.TextContent).text())
        }
    }

    @Test
    fun `a request without the credential or with a wrong one gets 401`() {
        val server = server().apply { start() }
        val (missingCode, missingBody) = post(server, authorization = null)
        assertEquals(401, missingCode)
        assertEquals("DTA agent server: missing or wrong credential", missingBody)
        assertEquals(401, post(server, authorization = "Bearer wrong").first)
        assertEquals(401, post(server, authorization = credential).first)
        assertEquals(200, post(server, authorization = "Bearer $credential").first)
    }

    @Test
    fun `it listens on loopback only`() {
        val server = server().apply { start() }
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), 2_000) }
        val external = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?: return // no non-loopback address on this machine: nothing else to try
        assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(external, server.port), 2_000) } }
    }

    @Test
    fun `a port in use is reported with the port`() {
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { taken ->
            val failure = assertFailsWith<PortInUseException> { server(taken.localPort).start() }
            assertEquals(taken.localPort, failure.port)
            assertTrue("${taken.localPort}" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `stop releases the port`() {
        val port = freePort()
        server(port).apply { start() }.stop()
        ServerSocket(port, 0, InetAddress.getLoopbackAddress()).close()
        server(port).apply { start() }
    }

    @Test
    fun `two clients are served at the same time`() {
        val server = server().apply { start() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val counts = List(2) {
                pool.submit(Callable { client(server).use { c -> c.listTools().tools().size } })
            }.map { it.get() }
            assertEquals(List(2) { Catalog.agentTools.size + 1 }, counts)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the server is a daemon client of kind mcp-http`() {
        val server = server().apply { start() }
        client(server).use { client ->
            client.callTool(McpSchema.CallToolRequest.builder().name("network_list").arguments(emptyMap<String, Any>()).build())
            val status = client.callTool(McpSchema.CallToolRequest.builder().name("daemon_status").arguments(emptyMap<String, Any>()).build())
            assertTrue("\"mcp-http\"" in (status.content().single() as McpSchema.TextContent).text())
        }
    }

    private inline fun <T> McpSyncClient.use(block: (McpSyncClient) -> T): T = try { block(this) } finally { closeGracefully() }
}
