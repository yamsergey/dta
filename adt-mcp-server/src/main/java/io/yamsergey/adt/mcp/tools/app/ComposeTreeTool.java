package io.yamsergey.adt.mcp.tools.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.yamsergey.adt.mcp.session.AppSession;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches the Compose UI hierarchy from an attached app.
 *
 * <p>Returns a tree structure showing all Compose composables with:
 * <ul>
 *   <li>Composable names and types</li>
 *   <li>Bounds (screen coordinates)</li>
 *   <li>Semantics IDs for accessibility</li>
 *   <li>Text content where applicable</li>
 * </ul>
 *
 * <p>Requires attach_app to be called first.
 */
public class ComposeTreeTool extends AdtTool {

    @Override
    public String getName() {
        return "compose_tree";
    }

    @Override
    public String getDescription() {
        return "Get Compose UI hierarchy. Use with FILTERS (text, text_exact, type) to find specific " +
               "elements - returns matching elements with paths and bounds for tapping. " +
               "For FULL tree analysis, use dump_layout instead to save to file and avoid context pollution. " +
               "Without filters, returns entire tree (can be large). Requires attach_app first.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "package": {
                            "type": "string",
                            "description": "Package name of the attached app. If not specified and only one app is attached, uses that."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        },
                        "text": {
                            "type": "string",
                            "description": "Filter: return only elements containing this text (case-insensitive). Returns matches with their hierarchy paths."
                        },
                        "text_exact": {
                            "type": "string",
                            "description": "Filter: return only elements with exactly this text. Returns matches with their hierarchy paths."
                        },
                        "type": {
                            "type": "string",
                            "description": "Filter: return only elements of this composable type (e.g., 'Button', 'Text', 'TextField'). Returns matches with their hierarchy paths."
                        }
                    }
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");
        String textFilter = getStringParam(args, "text");
        String textExactFilter = getStringParam(args, "text_exact");
        String typeFilter = getStringParam(args, "type");

        // Get session
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        // Fetch compose tree from sidekick
        Result<String> treeResult = fetchComposeTree(appSession);
        if (treeResult instanceof Failure<String> f) {
            return f;
        }
        String tree = ((Success<String>) treeResult).value();

        // Apply filters if any are specified
        boolean hasFilter = textFilter != null || textExactFilter != null || typeFilter != null;
        if (hasFilter) {
            return filterTree(tree, textFilter, textExactFilter, typeFilter, appSession.getPackageName());
        }

        return new Success<>(tree,
                String.format("Compose tree for %s. The tree shows the UI hierarchy with " +
                              "composable names, bounds, and semantics IDs. Use tap with coordinates " +
                              "to interact with elements.", appSession.getPackageName()));
    }

    /**
     * Resolves the session, handling defaults when only one session exists.
     */
    private Result<AppSession> resolveSession(SessionManager session, String device, String packageName) {
        List<AppSession> sessions = session.listSessions();

        if (sessions.isEmpty()) {
            return new Failure<>(null,
                    "No attached apps. Use attach_app first to connect to an app.");
        }

        // If only one session and no specific parameters, use it
        if (sessions.size() == 1 && packageName == null && device == null) {
            AppSession only = sessions.get(0);
            return new Success<>(only,
                    String.format("Using attached app: %s on %s", only.getPackageName(), only.getDevice()));
        }

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return new Failure<>(f.cause(), f.description());
            }
            device = ((Success<String>) deviceResult).value();
        }

        // If package not specified, check if there's only one app on this device
        if (packageName == null) {
            String finalDevice = device;
            List<AppSession> deviceSessions = sessions.stream()
                    .filter(s -> s.getDevice().equals(finalDevice))
                    .toList();

            if (deviceSessions.isEmpty()) {
                return new Failure<>(null,
                        String.format("No attached apps on %s. Use attach_app first.", device));
            }
            if (deviceSessions.size() == 1) {
                AppSession only = deviceSessions.get(0);
                return new Success<>(only,
                        String.format("Using attached app: %s", only.getPackageName()));
            }

            // Multiple apps, need to specify
            StringBuilder msg = new StringBuilder("Multiple apps attached on " + device + ". Specify package:\n");
            for (AppSession s : deviceSessions) {
                msg.append("  - ").append(s.getPackageName()).append("\n");
            }
            return new Failure<>(null, msg.toString());
        }

        return session.getSession(device, packageName);
    }

    /**
     * Fetches the compose tree from sidekick HTTP endpoint.
     */
    private Result<String> fetchComposeTree(AppSession appSession) {
        try {
            URL url = new URL(appSession.getSidekickUrl() + "/compose/tree");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return new Failure<>(null,
                        String.format("Sidekick returned %d. The app may have crashed or restarted. " +
                                      "Use attach_app to reconnect.", responseCode));
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }
            conn.disconnect();

            String tree = response.toString().trim();
            return new Success<>(tree,
                    String.format("Compose tree for %s. The tree shows the UI hierarchy with " +
                                  "composable names, bounds, and semantics IDs. Use tap with coordinates " +
                                  "to interact with elements.", appSession.getPackageName()));

        } catch (java.net.ConnectException e) {
            return new Failure<>(e,
                    "Cannot connect to sidekick. The app may have crashed. " +
                    "Check if the app is still running and use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to fetch compose tree: " + e.getMessage());
        }
    }

    /**
     * Filters the compose tree and returns matching elements with their paths.
     */
    private Result<?> filterTree(String treeJson, String textFilter, String textExactFilter,
                                  String typeFilter, String packageName) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(treeJson);

            List<ObjectNode> matches = new ArrayList<>();
            searchTree(root.path("root"), "", matches, textFilter, textExactFilter, typeFilter, mapper);

            // Build result
            ObjectNode result = mapper.createObjectNode();
            result.put("count", matches.size());
            result.put("package", packageName);

            // Add filter info
            ObjectNode filters = mapper.createObjectNode();
            if (textFilter != null) filters.put("text", textFilter);
            if (textExactFilter != null) filters.put("text_exact", textExactFilter);
            if (typeFilter != null) filters.put("type", typeFilter);
            result.set("filters", filters);

            ArrayNode matchesArray = mapper.createArrayNode();
            for (ObjectNode match : matches) {
                matchesArray.add(match);
            }
            result.set("matches", matchesArray);

            String description = matches.isEmpty()
                    ? "No elements found matching the filter criteria."
                    : String.format("Found %d matching element(s). Use the bounds to tap on elements.", matches.size());

            return new Success<>(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), description);

        } catch (Exception e) {
            return new Failure<>(e, "Failed to filter compose tree: " + e.getMessage());
        }
    }

    /**
     * Recursively searches the tree for matching elements.
     */
    private void searchTree(JsonNode node, String path, List<ObjectNode> matches,
                            String textFilter, String textExactFilter, String typeFilter,
                            ObjectMapper mapper) {
        if (node == null || node.isMissingNode()) {
            return;
        }

        String composable = node.path("composable").asText("");
        String currentPath = path.isEmpty() ? composable : path + " > " + composable;

        // Check if this node matches the filters
        boolean isMatch = checkMatch(node, textFilter, textExactFilter, typeFilter);

        if (isMatch) {
            ObjectNode match = mapper.createObjectNode();
            match.put("path", currentPath);
            match.put("composable", composable);

            // Include text if present
            if (node.has("text")) {
                match.put("text", node.path("text").asText());
            }

            // Include bounds if present
            if (node.has("bounds")) {
                match.set("bounds", node.path("bounds").deepCopy());
            }

            // Include semanticsId if present
            if (node.has("semanticsId")) {
                match.put("semanticsId", node.path("semanticsId").asInt());
            }

            // Include role if present
            if (node.has("role")) {
                match.put("role", node.path("role").asText());
            }

            // Include testTag if present
            if (node.has("testTag")) {
                match.put("testTag", node.path("testTag").asText());
            }

            matches.add(match);
        }

        // Recurse into children
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                searchTree(child, currentPath, matches, textFilter, textExactFilter, typeFilter, mapper);
            }
        }
    }

    /**
     * Checks if a node matches the filter criteria.
     */
    private boolean checkMatch(JsonNode node, String textFilter, String textExactFilter, String typeFilter) {
        // Check type filter
        if (typeFilter != null) {
            String composable = node.path("composable").asText("");
            if (!composable.equalsIgnoreCase(typeFilter)) {
                return false;
            }
        }

        // Check text filters
        String nodeText = node.path("text").asText(null);

        if (textExactFilter != null) {
            if (nodeText == null || !nodeText.equals(textExactFilter)) {
                return false;
            }
        }

        if (textFilter != null) {
            if (nodeText == null || !nodeText.toLowerCase().contains(textFilter.toLowerCase())) {
                return false;
            }
        }

        // If we have filters but no text filter matched yet, need at least one filter to have matched
        // If only type filter was specified, we matched above
        // If text filter was specified, we need text to match
        if (textFilter != null || textExactFilter != null) {
            return true; // Text filter was checked above
        }

        // Only type filter was specified (or no filters, but this method isn't called without filters)
        return typeFilter != null;
    }
}
