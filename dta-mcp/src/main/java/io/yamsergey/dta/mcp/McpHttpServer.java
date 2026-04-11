package io.yamsergey.dta.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Hosts the DTA MCP server over HTTP using the MCP Java SDK's
 * {@link HttpServletStreamableServerTransportProvider}, mounted on a
 * standalone embedded Jetty instance.
 *
 * <p>Tool definitions come from {@link McpServer#buildToolList()} — the same
 * source of truth used by the stdio transport. Adding a new tool means
 * editing one place in {@code McpServer.java}, and both transports pick it up.</p>
 *
 * <p>The MCP HTTP server runs on a <em>separate</em> port from the daemon's
 * REST API on purpose:</p>
 * <ul>
 *   <li>The daemon port is internal coordination, dynamically assigned, and
 *       lives at {@code ~/.dta/daemon.json}.</li>
 *   <li>The MCP port is external integration, user-picked, and stable so
 *       agent configs (Android Studio Gemini's {@code mcp.json}, Claude Code,
 *       Cursor, etc.) don't need rewriting on every restart.</li>
 * </ul>
 *
 * <p>Endpoint: {@code http://localhost:&lt;port&gt;/mcp} (Streamable HTTP transport).
 * Compatible with Android Studio Gemini's {@code httpUrl} field and any other
 * MCP client that supports the Streamable HTTP transport.</p>
 *
 * <p>Lifecycle: {@link #start(int)} returns the actual bound port (useful when
 * passing {@code port = 0} for an OS-assigned port). {@link #stop()} releases
 * resources and closes the daemon connection. The instance is single-shot —
 * create a new one to restart on a different port.</p>
 */
public class McpHttpServer {

    private static final Logger log = LoggerFactory.getLogger(McpHttpServer.class);

    private Server jettyServer;
    private int boundPort = -1;

    /**
     * Starts the embedded Jetty server, mounts the MCP streamable transport
     * servlet at {@code /mcp/*}, and binds to the given port.
     *
     * @param port the port to bind, or 0 for an OS-assigned port
     * @return the actual port the server is listening on
     * @throws Exception if the Jetty server fails to start (e.g. port in use)
     */
    public synchronized int start(int port) throws Exception {
        if (jettyServer != null) {
            throw new IllegalStateException("McpHttpServer already started on port " + boundPort);
        }

        log.info("Starting DTA MCP HTTP server v{} on port {}", McpServer.getVersion(), port);

        // Build the streamable HTTP transport provider. The default endpoint
        // path "/mcp" is what Android Studio Gemini and other clients expect,
        // so we don't override it.
        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(McpServer.getMcpJsonMapper())
            .build();

        // Build the MCP server using the SAME tool list as the stdio path.
        List<McpServerFeatures.SyncToolSpecification> tools = McpServer.buildToolList();
        io.modelcontextprotocol.server.McpServer.sync(transportProvider)
            .serverInfo("dta-mcp", McpServer.getVersion())
            .capabilities(ServerCapabilities.builder().tools(true).build())
            .tools(tools)
            .build();

        // Wire the servlet into Jetty. The transport provider IS an HttpServlet —
        // we just mount it at /* on a ServletContextHandler. The MCP routing is
        // entirely inside the servlet (it handles /mcp internally based on
        // mcpEndpoint configured on the builder).
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transportProvider), "/*");

        jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setPort(port);
        jettyServer.addConnector(connector);
        jettyServer.setHandler(context);

        jettyServer.start();
        boundPort = connector.getLocalPort();
        log.info("DTA MCP HTTP server started: {} tools served at http://localhost:{}/mcp",
            tools.size(), boundPort);
        return boundPort;
    }

    /**
     * Stops the Jetty server and releases all resources. Idempotent — calling
     * stop() on an already-stopped instance is a no-op.
     */
    public synchronized void stop() {
        if (jettyServer == null) return;
        try {
            log.info("Stopping DTA MCP HTTP server on port {}", boundPort);
            jettyServer.stop();
            jettyServer.destroy();
        } catch (Exception e) {
            log.warn("Error while stopping MCP HTTP server: {}", e.getMessage());
        } finally {
            jettyServer = null;
            boundPort = -1;
        }
    }

    /**
     * Returns the port the server is currently bound to, or -1 if stopped.
     */
    public synchronized int getBoundPort() {
        return boundPort;
    }

    /**
     * Returns true if the server is currently running.
     */
    public synchronized boolean isRunning() {
        return jettyServer != null && jettyServer.isStarted();
    }
}
