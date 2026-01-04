package io.yamsergey.adt.tools.android.inspect;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Filters ViewNode trees based on various criteria to reduce output size.
 *
 * <p>This class supports filtering nodes by:</p>
 * <ul>
 *   <li>Text content (contains or regex match)</li>
 *   <li>Class name (contains or regex match)</li>
 *   <li>Resource ID (contains or regex match)</li>
 *   <li>Content description (contains or regex match)</li>
 *   <li>Clickable property</li>
 *   <li>Nodes with any text content</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * ViewNodeFilter filter = ViewNodeFilter.builder()
 *     .textPattern("Submit")
 *     .clickableOnly(true)
 *     .includeParents(true)
 *     .build();
 *
 * List&lt;ViewNodeFilter.FilteredNode&gt; results = filter.filter(rootNode);
 * </pre>
 */
@Getter
@Builder
public class ViewNodeFilter {

    /**
     * Filter by text content (substring match, case-insensitive).
     * If starts with "regex:" uses regex matching.
     */
    private final String textPattern;

    /**
     * Filter by class name (substring match, case-insensitive).
     * If starts with "regex:" uses regex matching.
     */
    private final String classPattern;

    /**
     * Filter by resource ID (substring match, case-insensitive).
     * If starts with "regex:" uses regex matching.
     */
    private final String resourceIdPattern;

    /**
     * Filter by content description (substring match, case-insensitive).
     * If starts with "regex:" uses regex matching.
     */
    private final String contentDescPattern;

    /**
     * If true, only return clickable nodes.
     */
    private final boolean clickableOnly;

    /**
     * If true, only return nodes that have non-empty text.
     */
    private final boolean withTextOnly;

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
        /**
         * The matching node.
         */
        private final ViewNode node;

        /**
         * Parent chain from root to this node (excluding the node itself).
         * Only populated if includeParents is true.
         * First element is the root, last element is the immediate parent.
         */
        private final List<ParentInfo> parents;

        /**
         * Minimal parent information to provide context without full node data.
         */
        @Getter
        @Builder
        public static class ParentInfo {
            private final String className;
            private final String resourceId;
            private final String text;
            private final ViewNode.Bounds bounds;
            private final boolean clickable;

            /**
             * Creates ParentInfo from a ViewNode.
             */
            public static ParentInfo fromNode(ViewNode node) {
                Boolean clickable = node.getProperties() != null
                    ? node.getProperties().get("clickable")
                    : null;
                return ParentInfo.builder()
                        .className(node.getClassName())
                        .resourceId(node.getResourceId())
                        .text(node.getText())
                        .bounds(node.getBounds())
                        .clickable(clickable != null && clickable)
                        .build();
            }
        }
    }

    /**
     * Filters the view hierarchy and returns matching nodes.
     *
     * @param root The root node of the view hierarchy
     * @return List of filtered nodes with optional parent context
     */
    public List<FilteredNode> filter(ViewNode root) {
        List<FilteredNode> results = new ArrayList<>();
        List<ViewNode> parentChain = new ArrayList<>();
        filterRecursive(root, parentChain, results);
        return results;
    }

    private void filterRecursive(ViewNode node, List<ViewNode> parentChain, List<FilteredNode> results) {
        if (matches(node)) {
            FilteredNode.FilteredNodeBuilder builder = FilteredNode.builder()
                    .node(stripChildren(node));

            if (includeParents && !parentChain.isEmpty()) {
                List<FilteredNode.ParentInfo> parentInfos = new ArrayList<>();
                for (ViewNode parent : parentChain) {
                    parentInfos.add(FilteredNode.ParentInfo.fromNode(parent));
                }
                builder.parents(parentInfos);
            } else {
                builder.parents(List.of());
            }

            results.add(builder.build());
        }

        // Recurse into children
        if (node.getChildren() != null) {
            parentChain.add(node);
            for (ViewNode child : node.getChildren()) {
                filterRecursive(child, parentChain, results);
            }
            parentChain.remove(parentChain.size() - 1);
        }
    }

    /**
     * Creates a copy of the node without children to reduce output size.
     */
    private ViewNode stripChildren(ViewNode node) {
        return ViewNode.builder()
                .index(node.getIndex())
                .className(node.getClassName())
                .packageName(node.getPackageName())
                .text(node.getText())
                .resourceId(node.getResourceId())
                .contentDesc(node.getContentDesc())
                .hint(node.getHint())
                .bounds(node.getBounds())
                .properties(node.getProperties())
                .attributes(node.getAttributes())
                .children(List.of()) // Empty children list
                .build();
    }

    /**
     * Checks if a node matches all specified filter criteria.
     */
    private boolean matches(ViewNode node) {
        // Check clickable filter
        if (clickableOnly) {
            Boolean clickable = node.getProperties() != null
                ? node.getProperties().get("clickable")
                : null;
            if (clickable == null || !clickable) {
                return false;
            }
        }

        // Check withText filter
        if (withTextOnly) {
            if (node.getText() == null || node.getText().isEmpty()) {
                return false;
            }
        }

        // Check text pattern
        if (textPattern != null && !textPattern.isEmpty()) {
            if (!matchesPattern(node.getText(), textPattern)) {
                return false;
            }
        }

        // Check class pattern
        if (classPattern != null && !classPattern.isEmpty()) {
            if (!matchesPattern(node.getClassName(), classPattern)) {
                return false;
            }
        }

        // Check resource ID pattern
        if (resourceIdPattern != null && !resourceIdPattern.isEmpty()) {
            if (!matchesPattern(node.getResourceId(), resourceIdPattern)) {
                return false;
            }
        }

        // Check content description pattern
        if (contentDescPattern != null && !contentDescPattern.isEmpty()) {
            if (!matchesPattern(node.getContentDesc(), contentDescPattern)) {
                return false;
            }
        }

        return true;
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
        return clickableOnly
            || withTextOnly
            || (textPattern != null && !textPattern.isEmpty())
            || (classPattern != null && !classPattern.isEmpty())
            || (resourceIdPattern != null && !resourceIdPattern.isEmpty())
            || (contentDescPattern != null && !contentDescPattern.isEmpty());
    }
}
