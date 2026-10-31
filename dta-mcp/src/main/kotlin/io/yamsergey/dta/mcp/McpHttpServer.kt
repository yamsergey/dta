package io.yamsergey.dta.mcp

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.VersionInfo
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.eclipse.jetty.ee11.servlet.FilterHolder
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.ee11.servlet.ServletHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.thread.QueuedThreadPool
import tools.jackson.databind.json.JsonMapper
import java.net.BindException
import java.security.MessageDigest
import java.util.EnumSet

/** The port is taken by another process. */
class PortInUseException(val port: Int) : Exception("port $port is in use")

/**
 * DTA's tools over MCP Streamable HTTP (feature 009 US4, contract agent-server.md), hosted by the
 * Android Studio plugin: `http://127.0.0.1:<port>/mcp`, loopback only, every request carrying
 * `Authorization: Bearer <credential>`. The tools are the stdio server's ([McpServerSpecs]); the
 * server is a daemon client like any other.
 */
class McpHttpServer(
    val port: Int,
    private val credential: String,
    private val connect: suspend () -> DaemonLauncher.Connected,
    private val bound: ResultBound = ResultBound(),
    private val log: (String) -> Unit = {},
    /** The projects `app_run` may run without a `project` argument: the IDE's open Android projects. */
    private val projects: () -> List<java.io.File> = { emptyList() },
    private val runApp: AppRunner = AppRunTool.real(io.yamsergey.dta.protocol.ops.ClientKind.MCP_HTTP),
) {
    val url: String = "http://$HOST:$port$ENDPOINT"

    private var jetty: Server? = null
    private var mcp: McpSyncServer? = null
    private var connection: DaemonConnection? = null

    /** Starts serving; [PortInUseException] when [port] is taken. */
    @Synchronized
    fun start() {
        check(jetty == null) { "already started" }
        // The SDK finds its JSON mapper through ServiceLoader on the context classloader; in the IDE that is not ours.
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = McpHttpServer::class.java.classLoader
        try {
            val connection = DaemonConnection(connect, log)
            val tools = McpTools(call = connection::call, bound = bound, unusable = { connection.fatal })
            val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
            val transport = HttpServletStreamableServerTransportProvider.builder().jsonMapper(mapper).mcpEndpoint(ENDPOINT).build()
            val mcp = McpServer.sync(transport)
                .serverInfo("dta", VersionInfo.current().version)
                .instructions(McpTools.INSTRUCTIONS)
                .capabilities(McpServerSpecs.CAPABILITIES)
                .tools(McpServerSpecs.tools(tools, mapper, AppRunTool(runApp, projects)))
                .build()

            val jetty = Server(QueuedThreadPool().apply { name = "dta-agent-server"; isDaemon = true })
            jetty.addConnector(ServerConnector(jetty).apply { host = HOST; port = this@McpHttpServer.port })
            jetty.handler = ServletContextHandler().apply {
                addFilter(FilterHolder(BearerFilter(credential)), "/*", EnumSet.of(DispatcherType.REQUEST))
                addServlet(ServletHolder(transport), ENDPOINT)
            }
            try {
                jetty.start()
            } catch (e: Exception) {
                runCatching { jetty.stop() }
                runCatching { mcp.close() }
                if (e.causes().any { it is BindException }) throw PortInUseException(port)
                throw e
            }
            this.jetty = jetty
            this.mcp = mcp
            this.connection = connection
            log("DTA agent server on $url")
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** Stops serving and releases the port. */
    @Synchronized
    fun stop() {
        runCatching { mcp?.closeGracefully() }
        runCatching { jetty?.stop() }
        connection?.let { runBlocking { it.close() } }
        mcp = null
        jetty = null
        connection = null
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    /** Admits only requests with the credential; everything else gets 401 and a plain sentence. */
    private class BearerFilter(credential: String) : Filter {
        private val expected = "Bearer $credential".toByteArray()

        override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
            val given = (request as HttpServletRequest).getHeader("Authorization")?.toByteArray()
            if (given != null && MessageDigest.isEqual(given, expected)) return chain.doFilter(request, response)
            (response as HttpServletResponse).status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "text/plain; charset=utf-8"
            response.writer.apply { write(UNAUTHORIZED); flush() }
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val ENDPOINT = "/mcp"
        const val DEFAULT_PORT = 12321
        const val UNAUTHORIZED = "DTA agent server: missing or wrong credential"
    }
}
