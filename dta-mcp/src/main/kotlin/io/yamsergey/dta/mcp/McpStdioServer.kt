package io.yamsergey.dta.mcp

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.DtaHome
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch

/**
 * DTA's tools over MCP stdio, served by `dta mcp` (feature 008 US2). Standard output carries protocol
 * frames only; everything else goes to `$DTA_HOME/mcp.log` (FR-018). Connects to the shared
 * daemon lazily and reconnects after the daemon was replaced or stopped.
 */
object McpStdioServer {

    fun run(
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
        connect: suspend () -> DaemonLauncher.Connected = { DaemonLauncher().ensure(ClientKind.MCP) },
        logDir: File = DtaHome.resolve().root,
        redirectStd: Boolean = true,
    ): Int {
        val log = PrintStream(FileOutputStream(File(logDir.also { it.mkdirs() }, "mcp.log"), true), true)
        // Nothing but protocol frames may reach the agent's stdout.
        val protocolOut = output
        if (redirectStd) {
            System.setOut(log)
            System.setErr(log)
        }

        val version = VersionInfo.current()
        val unusable = javaUnusable()
        val connection = Connection(connect, log)
        val tools = McpTools(
            call = connection::call,
            bound = ResultBound.fromEnv(),
            unusable = { unusable ?: connection.fatal },
        )
        val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())

        val ended = CountDownLatch(1)
        val watchedInput = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it < 0) ended.countDown() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it < 0) ended.countDown() }
        }
        val transport = StdioServerTransportProvider(mapper, watchedInput, protocolOut)
        val specs = tools.tools.map { spec ->
            McpServerFeatures.SyncToolSpecification.builder()
                .tool(
                    McpSchema.Tool.builder()
                        .name(spec.toolName)
                        .description(spec.description)
                        .inputSchema(mapper, DtaJson.encodeToString(JsonObject.serializer(), spec.inputSchema()))
                        .build(),
                )
                .callHandler { _, request ->
                    val args = McpTools.arguments(request.arguments()) { mapper.writeValueAsString(it) }
                    val outcome = runBlocking { tools.invoke(spec.toolName, args) }
                    McpSchema.CallToolResult.builder().addTextContent(outcome.text).isError(outcome.isError).build()
                }
                .build()
        }
        val server = McpServer.sync(transport)
            .serverInfo("dta", version.version)
            .instructions(McpTools.INSTRUCTIONS)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .tools(specs)
            .build()
        log.println("dta mcp ${version.display} serving ${specs.size} tools")
        ended.await()
        runCatching { server.closeGracefully() }
        runBlocking { connection.close() }
        return 0
    }

    /** The CLI needs Java 21; on an older JVM every tool says so instead of the server failing to start. */
    private fun javaUnusable(): String? {
        val feature = Runtime.version().feature()
        return if (feature < 21) "DTA needs Java 21 or newer; this is Java $feature. Install Java 21 and restart the agent." else null
    }

    /** One daemon connection shared by all tool calls, re-established when it goes away. */
    private class Connection(private val connect: suspend () -> DaemonLauncher.Connected, private val log: PrintStream) {
        private val mutex = Mutex()
        private var client: DaemonClient? = null

        @Volatile
        var fatal: String? = null
            private set

        suspend fun call(operation: String, params: JsonObject): Selected {
            val current = mutex.withLock {
                client?.takeIf { it.goneReason == null } ?: try {
                    connect().also { c -> c.statements.forEach { log.println(it) } }.client.also { client = it }
                } catch (e: VersionConflictException) {
                    fatal = e.message
                    throw io.yamsergey.dta.client.DaemonGoneException(e.message!!)
                } catch (e: DaemonStartException) {
                    throw io.yamsergey.dta.client.DaemonGoneException(e.message!!)
                }
            }
            return current.call(operation, params)
        }

        suspend fun close() = mutex.withLock { client?.let { runCatching { it.close() } } }
    }
}
