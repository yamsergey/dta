package io.yamsergey.dta.sidekick.layout;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filters the unified layout tree by text, type, resource ID, or view ID.
 *
 * <p>Works on both View nodes (matching className, resourceId, text) and
 * Compose nodes (matching composable name, text semantics).</p>
 *
 * <p>Returns matching nodes with their parent chain for context.</p>
 */
public class UnifiedTreeFilter {

    private static final String TAG = "UnifiedTreeFilter";

    /**
     * Filters the unified tree, returning matching nodes.
     *
     * @param tree the full unified tree result from UnifiedTreeBuilder
     * @param textFilter text content filter (substring match, case-insensitive)
     * @param typeFilter type/class filter (substring match for className or composable name)
     * @param resourceIdFilter resource ID filter (substring match)
     * @param viewIdFilter specific view drawing ID to find (returns subtree)
     * @return filtered result map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> filter(Map<String, Object> tree,
                                              String textFilter, String typeFilter,
                                              String resourceIdFilter, String viewIdFilter) {
        if (tree == null) return null;

        // If viewId filter is specified, find and return the subtree
        if (viewIdFilter != null && !viewIdFilter.isEmpty()) {
            return filterByViewId(tree, viewIdFilter);
        }

        // No filters specified - return as-is
        if (isEmpty(textFilter) && isEmpty(typeFilter) && isEmpty(resourceIdFilter)) {
            return tree;
        }

        // Apply filters to each window's tree
        Map<String, Object> result = new HashMap<>(tree);
        Object windowsObj = tree.get("windows");
        if (windowsObj instanceof List) {
            List<Map<String, Object>> filteredWindows = new ArrayList<>();
            for (Object windowObj : (List<?>) windowsObj) {
                if (windowObj instanceof Map) {
                    Map<String, Object> window = (Map<String, Object>) windowObj;
                    Map<String, Object> windowTree = (Map<String, Object>) window.get("tree");
                    if (windowTree != null) {
                        List<Map<String, Object>> matches = new ArrayList<>();
                        collectMatches(windowTree, textFilter, typeFilter, resourceIdFilter,
                            matches, new ArrayList<>());
                        if (!matches.isEmpty()) {
                            Map<String, Object> filteredWindow = new HashMap<>(window);
                            filteredWindow.put("matches", matches);
                            filteredWindow.put("matchCount", matches.size());
                            filteredWindow.remove("tree"); // Replace tree with matches
                            filteredWindows.add(filteredWindow);
                        }
                    }
                }
            }
            result.put("windows", filteredWindows);

            // Add filter info
            Map<String, Object> filters = new HashMap<>();
            if (!isEmpty(textFilter)) filters.put("text", textFilter);
            if (!isEmpty(typeFilter)) filters.put("type", typeFilter);
            if (!isEmpty(resourceIdFilter)) filters.put("resourceId", resourceIdFilter);
            result.put("filters", filters);

            int totalMatches = filteredWindows.stream()
                .mapToInt(w -> ((Number) w.getOrDefault("matchCount", 0)).intValue())
                .sum();
            result.put("totalMatches", totalMatches);
        }

        return result;
    }

    /**
     * Collects all nodes matching the given filters.
     */
    @SuppressWarnings("unchecked")
    private static void collectMatches(Map<String, Object> node,
                                        String textFilter, String typeFilter, String resourceIdFilter,
                                        List<Map<String, Object>> matches,
                                        List<String> parentChain) {
        if (node == null) return;

        boolean isMatch = matchesFilters(node, textFilter, typeFilter, resourceIdFilter);

        if (isMatch) {
            Map<String, Object> match = new HashMap<>(node);
            // Remove children from match to keep output compact
            match.remove("children");
            if (!parentChain.isEmpty()) {
                match.put("parentChain", new ArrayList<>(parentChain));
            }
            matches.add(match);
        }

        // Build parent identifier for chain
        String nodeId = getNodeIdentifier(node);

        // Recurse into children
        Object childrenObj = node.get("children");
        if (childrenObj instanceof List) {
            List<String> childParentChain = new ArrayList<>(parentChain);
            childParentChain.add(nodeId);
            for (Object child : (List<?>) childrenObj) {
                if (child instanceof Map) {
                    collectMatches((Map<String, Object>) child,
                        textFilter, typeFilter, resourceIdFilter,
                        matches, childParentChain);
                }
            }
        }
    }

    /**
     * Checks if a node matches the given filters.
     */
    private static boolean matchesFilters(Map<String, Object> node,
                                           String textFilter, String typeFilter, String resourceIdFilter) {
        boolean textMatch = isEmpty(textFilter) || matchesText(node, textFilter);
        boolean typeMatch = isEmpty(typeFilter) || matchesType(node, typeFilter);
        boolean resourceMatch = isEmpty(resourceIdFilter) || matchesResourceId(node, resourceIdFilter);

        return textMatch && typeMatch && resourceMatch;
    }

    /**
     * Checks if a node's text content matches the filter.
     */
    private static boolean matchesText(Map<String, Object> node, String textFilter) {
        String lower = textFilter.toLowerCase();

        // Check View text
        Object text = node.get("text");
        if (text != null && text.toString().toLowerCase().contains(lower)) return true;

        // Check hint
        Object hint = node.get("hint");
        if (hint != null && hint.toString().toLowerCase().contains(lower)) return true;

        // Check contentDescription
        Object desc = node.get("contentDescription");
        if (desc != null && desc.toString().toLowerCase().contains(lower)) return true;

        return false;
    }

    /**
     * Checks if a node's type/class matches the filter.
     */
    private static boolean matchesType(Map<String, Object> node, String typeFilter) {
        String lower = typeFilter.toLowerCase();

        // Check View className
        Object className = node.get("className");
        if (className != null && className.toString().toLowerCase().contains(lower)) return true;

        // Check Compose composable name
        Object composable = node.get("composable");
        if (composable != null && composable.toString().toLowerCase().contains(lower)) return true;

        return false;
    }

    /**
     * Checks if a node's resource ID matches the filter.
     */
    private static boolean matchesResourceId(Map<String, Object> node, String resourceIdFilter) {
        Object resourceId = node.get("resourceId");
        return resourceId != null && resourceId.toString().toLowerCase()
            .contains(resourceIdFilter.toLowerCase());
    }

    /**
     * Finds a subtree by view drawing ID.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterByViewId(Map<String, Object> tree, String viewIdStr) {
        long targetId;
        try {
            targetId = Long.parseLong(viewIdStr);
        } catch (NumberFormatException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid view_id: " + viewIdStr);
            return error;
        }

        Object windowsObj = tree.get("windows");
        if (windowsObj instanceof List) {
            for (Object windowObj : (List<?>) windowsObj) {
                if (windowObj instanceof Map) {
                    Map<String, Object> window = (Map<String, Object>) windowObj;
                    Map<String, Object> windowTree = (Map<String, Object>) window.get("tree");
                    if (windowTree != null) {
                        Map<String, Object> found = findNodeByDrawingId(windowTree, targetId);
                        if (found != null) {
                            Map<String, Object> result = new HashMap<>(tree);
                            result.put("subtree", found);
                            result.remove("windows");
                            result.put("viewId", viewIdStr);
                            return result;
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>(tree);
        result.put("error", "View not found with drawingId: " + viewIdStr);
        return result;
    }

    /**
     * Recursively finds a node by its drawing ID.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNodeByDrawingId(Map<String, Object> node, long targetId) {
        if (node == null) return null;

        Object drawingIdObj = node.get("drawingId");
        if (drawingIdObj != null && ((Number) drawingIdObj).longValue() == targetId) {
            return node;
        }

        Object childrenObj = node.get("children");
        if (childrenObj instanceof List) {
            for (Object child : (List<?>) childrenObj) {
                if (child instanceof Map) {
                    Map<String, Object> found = findNodeByDrawingId((Map<String, Object>) child, targetId);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    /**
     * Gets a short identifier for a node (for parent chain display).
     */
    private static String getNodeIdentifier(Map<String, Object> node) {
        // Prefer composable name for Compose nodes
        Object composable = node.get("composable");
        if (composable != null) return composable.toString();

        // Use resource ID if available
        Object resourceId = node.get("resourceId");
        if (resourceId != null) return resourceId.toString();

        // Use simple class name
        Object className = node.get("className");
        if (className != null) {
            String name = className.toString();
            int lastDot = name.lastIndexOf('.');
            return lastDot >= 0 ? name.substring(lastDot + 1) : name;
        }

        return "unknown";
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
