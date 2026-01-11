package io.yamsergey.adt.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Base class for ADT MCP tools.
 *
 * <p>Provides common functionality for tool implementation:
 * <ul>
 *   <li>JSON schema generation for parameters</li>
 *   <li>Result-to-CallToolResult conversion with agent guidance</li>
 *   <li>Common parameter extraction helpers</li>
 * </ul>
 *
 * <p>Tools should extend this class and implement {@link #execute(Map, SessionManager)}.
 */
public abstract class AdtTool {

    protected static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Returns the tool name (e.g., "list_devices").
     */
    public abstract String getName();

    /**
     * Returns the tool description for the agent.
     */
    public abstract String getDescription();

    /**
     * Returns the JSON Schema for tool parameters.
     * Override to define parameters; default is no parameters.
     */
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
                """;
    }

    /**
     * Executes the tool with given arguments.
     *
     * @param args parsed arguments from JSON
     * @param session session manager for device/app state
     * @return Result with value or failure
     */
    public abstract Result<?> execute(Map<String, Object> args, SessionManager session);

    /**
     * Creates the MCP SyncToolSpecification for this tool.
     */
    public McpServerFeatures.SyncToolSpecification toSpecification(SessionManager session) {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(getName(), getDescription(), getInputSchema()),
                (exchange, args) -> {
                    Result<?> result = execute(args, session);
                    return toCallToolResult(result);
                }
        );
    }

    /**
     * Converts Result to MCP CallToolResult.
     * Uses description for agent guidance.
     */
    protected McpSchema.CallToolResult toCallToolResult(Result<?> result) {
        return switch (result) {
            case Success<?> s -> {
                String content = formatSuccessContent(s);
                yield McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(content)))
                        .isError(false)
                        .build();
            }
            case Failure<?> f -> McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(f.description())))
                    .isError(true)
                    .build();
        };
    }

    /**
     * Formats success result content.
     * Includes description (agent guidance) + formatted value.
     */
    protected String formatSuccessContent(Success<?> success) {
        StringBuilder sb = new StringBuilder();

        // Agent guidance first
        if (success.description() != null && !success.description().isEmpty()) {
            sb.append(success.description()).append("\n\n");
        }

        // Format value
        Object value = success.value();
        if (value != null) {
            sb.append(formatValue(value));
        }

        return sb.toString().trim();
    }

    /**
     * Formats a value for output.
     * Override for custom formatting.
     */
    protected String formatValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof byte[] bytes) {
            return "[Binary data: " + bytes.length + " bytes]";
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }

    /**
     * Creates a CallToolResult with image content (base64 PNG).
     */
    protected McpSchema.CallToolResult imageResult(byte[] imageData, String description) {
        String base64 = Base64.getEncoder().encodeToString(imageData);
        // ImageContent(annotations, priority, data, mimeType)
        return McpSchema.CallToolResult.builder()
                .content(List.of(
                        new McpSchema.TextContent(description),
                        new McpSchema.ImageContent(null, null, base64, "image/png")
                ))
                .isError(false)
                .build();
    }

    // Parameter extraction helpers

    protected String getStringParam(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value != null ? value.toString() : null;
    }

    protected String getStringParam(Map<String, Object> args, String name, String defaultValue) {
        String value = getStringParam(args, name);
        return value != null ? value : defaultValue;
    }

    protected Integer getIntParam(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected int getIntParam(Map<String, Object> args, String name, int defaultValue) {
        Integer value = getIntParam(args, name);
        return value != null ? value : defaultValue;
    }

    protected Boolean getBoolParam(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    protected boolean getBoolParam(Map<String, Object> args, String name, boolean defaultValue) {
        Boolean value = getBoolParam(args, name);
        return value != null ? value : defaultValue;
    }
}
