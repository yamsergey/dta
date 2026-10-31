package io.yamsergey.dta.mcp

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DtaHome
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import kotlinx.coroutines.runBlocking
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
        val connection = DaemonConnection(connect, log::println)
        val tools = McpTools(
            call = connection::call,
            bound = ResultBound.fromEnv(),
            unusable = { unusable ?: connection.fatal },
            schemaFolder = File(System.getProperty("user.dir")),
        )
        val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())

        val ended = CountDownLatch(1)
        val watchedInput = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it < 0) ended.countDown() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it < 0) ended.countDown() }
        }
        val transport = StdioServerTransportProvider(mapper, watchedInput, protocolOut)
        val appRun = AppRunTool(AppRunTool.real(ClientKind.MCP), projects = { listOf(File(System.getProperty("user.dir"))) })
        val specs = McpServerSpecs.tools(tools, mapper, appRun)
        val server = McpServer.sync(transport)
            .serverInfo("dta", version.version)
            .instructions(McpTools.INSTRUCTIONS)
            .capabilities(McpServerSpecs.CAPABILITIES)
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
}
