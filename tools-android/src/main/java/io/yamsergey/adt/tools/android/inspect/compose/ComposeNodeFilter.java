package io.yamsergey.adt.tools.android.inspect.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Filters Compose tree JSON based on various criteria to reduce output size.
 *
 * <p>This class supports filtering nodes by:</p>
 * <ul>
 *   <li>Composable name (e.g., "Button", "Text")</li>
 *   <li>Text content</li>
 *   <li>Role (e.g., "Button", "Image")</li>
 *   <li>Test tag</li>
 *   <li>Nodes with any text content</li>
 *   <li>Nodes with role (interactive elements)</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * ComposeNodeFilter filter = ComposeNodeFilter.builder()
 *     .composablePattern("Button")
 *     .includeParents(true)
 *     .build();
 *
 * String filteredJson = filter.filter(composeTreeJson);
 * </pre>
 */
@Getter
@Builder
public class ComposeNodeFilter {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Filter by composable name (substring match, case-insensitive).
     * If starts with "regex:" uses regex matching.
     */
    private final String composablePattern;

    /**
     * Filter by text content (substring match, case-insensitive).
     * If starts with "regex:" uses regex matching.
     */
    private final String textPattern;

    /**
     * Filter by role (substring match, case-insensitive).
     * Common roles: Button, Image, Checkbox, Switch, etc.
     */
    private final String rolePattern;

    /**
     * Filter by test tag (substring match, case-insensitive).
     */
    private final String testTagPattern;

    /**
     * If true, only return nodes that have non-empty text.
     */
    private final boolean withTextOnly;

    /**
     * If true, only return nodes that have a role (interactive elements).
     */
    private final boolean withRoleOnly;

    /**
     * If true, include parent chain for each matching node.
     */
    private final boolean includeParents;

    /**
     * Represents a filtered node with optional parent context.
     */
    @Getter
    @Builder
    public static class FilteredNode {
        private final JsonNode node;
        private final List<ParentInfo> parents;

        @Getter
        @Builder
        public static class ParentInfo {
            private final String composable;
            private final String role;
            private final JsonNode bounds;
        }
    }

    /**
     * Filters the Compose tree JSON and returns matching nodes.
     *
     * @param composeTreeJson The JSON string from /compose/tree endpoint
     * @return Filtered JSON string with matching nodes
     */
    public String filter(String composeTreeJson) throws Exception {
        JsonNode root = mapper.readTree(composeTreeJson);

        // Navigate to the actual root node
        JsonNode treeRoot = root.has("root") ? root.get("root") : root;

        List<FilteredNode> results = new ArrayList<>();
        List<JsonNode> parentChain = new ArrayList<>();

        filterRecursive(treeRoot, parentChain, results);

        // Build result array
        ArrayNode resultArray = mapper.createArrayNode();
        for (FilteredNode filtered : results) {
            ObjectNode entry = mapper.createObjectNode();
            entry.set("node", stripChildren(filtered.getNode()));

            ArrayNode parentsArray = mapper.createArrayNode();
            if (filtered.getParents() != null) {
                for (FilteredNode.ParentInfo parent : filtered.getParents()) {
                    ObjectNode parentObj = mapper.createObjectNode();
                    parentObj.put("composable", parent.getComposable());
                    if (parent.getRole() != null) {
                        parentObj.put("role", parent.getRole());
                    }
                    if (parent.getBounds() != null) {
                        parentObj.set("bounds", parent.getBounds());
                    }
                    parentsArray.add(parentObj);
                }
            }
            entry.set("parents", parentsArray);

            resultArray.add(entry);
        }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultArray);
    }

    private void filterRecursive(JsonNode node, List<JsonNode> parentChain, List<FilteredNode> results) {
        if (node == null || !node.isObject()) {
            return;
        }

        if (matches(node)) {
            List<FilteredNode.ParentInfo> parentInfos = new ArrayList<>();
            if (includeParents) {
                for (JsonNode parent : parentChain) {
                    parentInfos.add(FilteredNode.ParentInfo.builder()
                            .composable(getTextValue(parent, "composable"))
                            .role(getTextValue(parent, "role"))
                            .bounds(parent.get("bounds"))
                            .build());
                }
            }

            results.add(FilteredNode.builder()
                    .node(node)
                    .parents(parentInfos)
                    .build());
        }

        // Recurse into children
        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            parentChain.add(node);
            for (JsonNode child : children) {
                filterRecursive(child, parentChain, results);
            }
            parentChain.remove(parentChain.size() - 1);
        }
    }

    /**
     * Creates a copy of the node without children to reduce output size.
     */
    private JsonNode stripChildren(JsonNode node) {
        if (!node.isObject()) {
            return node;
        }

        ObjectNode copy = mapper.createObjectNode();
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!"children".equals(fieldName)) {
                copy.set(fieldName, node.get(fieldName));
            }
        }
        return copy;
    }

    /**
     * Checks if a node matches all specified filter criteria.
     */
    private boolean matches(JsonNode node) {
        // Check withText filter
        if (withTextOnly) {
            String text = getTextValue(node, "text");
            if (text == null || text.isEmpty()) {
                return false;
            }
        }

        // Check withRole filter
        if (withRoleOnly) {
            String role = getTextValue(node, "role");
            if (role == null || role.isEmpty()) {
                return false;
            }
        }

        // Check composable pattern
        if (composablePattern != null && !composablePattern.isEmpty()) {
            if (!matchesPattern(getTextValue(node, "composable"), composablePattern)) {
                return false;
            }
        }

        // Check text pattern
        if (textPattern != null && !textPattern.isEmpty()) {
            if (!matchesPattern(getTextValue(node, "text"), textPattern)) {
                return false;
            }
        }

        // Check role pattern
        if (rolePattern != null && !rolePattern.isEmpty()) {
            if (!matchesPattern(getTextValue(node, "role"), rolePattern)) {
                return false;
            }
        }

        // Check testTag pattern
        if (testTagPattern != null && !testTagPattern.isEmpty()) {
            if (!matchesPattern(getTextValue(node, "testTag"), testTagPattern)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Gets a text value from a JSON node.
     */
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && field.isTextual() ? field.asText() : null;
    }

    /**
     * Matches a value against a pattern.
     * If pattern starts with "regex:", uses regex matching.
     * Otherwise, does case-insensitive substring match.
     */
    private boolean matchesPattern(String value, String pattern) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        if (pattern.startsWith("regex:")) {
            String regex = pattern.substring(6);
            try {
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).find();
            } catch (Exception e) {
                // Invalid regex, fall back to substring match
                return value.toLowerCase().contains(regex.toLowerCase());
            }
        } else {
            return value.toLowerCase().contains(pattern.toLowerCase());
        }
    }

    /**
     * Checks if any filter criteria are specified.
     */
    public boolean hasFilters() {
        return withTextOnly
            || withRoleOnly
            || (composablePattern != null && !composablePattern.isEmpty())
            || (textPattern != null && !textPattern.isEmpty())
            || (rolePattern != null && !rolePattern.isEmpty())
            || (testTagPattern != null && !testTagPattern.isEmpty());
    }
}
