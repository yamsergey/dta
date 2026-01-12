package io.yamsergey.adt.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.mcp.tools.app.*;
import io.yamsergey.adt.mcp.tools.debug.*;
import io.yamsergey.adt.mcp.tools.device.*;
import io.yamsergey.adt.mcp.tools.session.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server for Android Development Tools.
 *
 * <p>Provides tools for coding agents to interact with Android devices and apps:
 * <ul>
 *   <li>Device tools - list devices, capture screenshots, perform gestures</li>
 *   <li>Session tools - attach/detach from apps for deep inspection</li>
 *   <li>App tools - inspect Compose UI, monitor network traffic</li>
 * </ul>
 *
 * <p>Usage with Claude Desktop/Code:
 * <pre>
 * {
 *   "mcpServers": {
 *     "adt": {
 *       "command": "/path/to/adt-mcp",
 *       "args": []
 *     }
 *   }
 * }
 * </pre>
 */
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final String SERVER_NAME = "adt-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    private final SessionManager sessionManager;
    private final List<AdtTool> tools;

    public McpServer() {
        this.sessionManager = new SessionManager();
        this.tools = createTools();
    }

    /**
     * Creates all available tools.
     */
    private List<AdtTool> createTools() {
        List<AdtTool> toolList = new ArrayList<>();

        // Device tools (no app attachment required)
        toolList.add(new ListDevicesTool());
        toolList.add(new ListPackagesTool());
        toolList.add(new ScreenshotTool());
        toolList.add(new TapTool());
        toolList.add(new SwipeTool());
        toolList.add(new InputTextTool());
        toolList.add(new PressKeyTool());

        // Session tools (manage app connections)
        toolList.add(new ListSessionsTool());
        toolList.add(new AttachAppTool());
        toolList.add(new DetachAppTool());

        // App tools (require sidekick attachment)
        toolList.add(new ComposeTreeTool());
        toolList.add(new NetworkRequestsTool());
        toolList.add(new NetworkRequestTool());
        toolList.add(new WebSocketConnectionsTool());
        toolList.add(new WebSocketConnectionTool());

        // Debug server tools
        toolList.add(new StartDebugServerTool());
        toolList.add(new StopDebugServerTool());
        toolList.add(new GetSelectedElementTool());
        toolList.add(new GetSelectedNetworkRequestTool(sessionManager));

        return toolList;
    }

    /**
     * Starts the MCP server with stdio transport.
     */
    public void start() {
        log.info("Starting {} v{}", SERVER_NAME, SERVER_VERSION);

        // Create stdio transport
        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(new ObjectMapper());

        // Convert tools to MCP specifications
        McpServerFeatures.SyncToolSpecification[] toolSpecs = tools.stream()
                .map(tool -> tool.toSpecification(sessionManager))
                .toArray(McpServerFeatures.SyncToolSpecification[]::new);

        // Build and start server
        McpSyncServer server = io.modelcontextprotocol.server.McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .logging()
                        .build())
                .tools(toolSpecs)
                .build();

        log.info("MCP server started with {} tools", tools.size());
        logAvailableTools();

        // Register shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down MCP server...");
            sessionManager.cleanup();
            server.close();
            log.info("MCP server stopped");
        }));
    }

    /**
     * Logs all available tools for debugging.
     */
    private void logAvailableTools() {
        log.debug("Available tools:");
        for (AdtTool tool : tools) {
            log.debug("  - {}: {}", tool.getName(), tool.getDescription());
        }
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        // Configure logging to stderr (stdout is for MCP protocol)
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss");

        McpServer server = new McpServer();
        server.start();
    }
}
