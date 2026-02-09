package io.yamsergey.dta.tools.android.inspect.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds scrollable views in a UI hierarchy.
 *
 * <p>This class traverses a {@link ViewNode} tree to identify views that are marked
 * as scrollable. It can find all scrollable views, search by resource ID, or
 * automatically select the best default scrollable view.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * ScrollableViewFinder finder = new ScrollableViewFinder();
 *
 * // Find the default (largest) scrollable view
 * Optional&lt;ViewNode&gt; scrollable = finder.findDefaultScrollable(rootNode);
 *
 * // Find by resource ID
 * Optional&lt;ViewNode&gt; specific = finder.findByResourceId(rootNode, "com.app:id/recycler");
 *
 * // Find all scrollable views
 * List&lt;ViewNode&gt; allScrollable = finder.findScrollableViews(rootNode);
 * </pre>
 */
public class ScrollableViewFinder {

    /**
     * Minimum area (in pixels squared) for a scrollable view to be considered valid.
     * Filters out tiny or invisible scrollable views.
     */
    private static final int MIN_SCROLLABLE_AREA = 10000; // ~100x100 pixels

    /**
     * Finds all scrollable views in the hierarchy.
     *
     * @param root The root node of the view hierarchy
     * @return List of scrollable ViewNodes, sorted by area (largest first)
     */
    public List<ViewNode> findScrollableViews(ViewNode root) {
        List<ViewNode> scrollables = new ArrayList<>();
        collectScrollableViews(root, scrollables);

        // Sort by area (largest first)
        scrollables.sort(Comparator.comparingInt(this::getViewArea).reversed());

        return scrollables;
    }

    /**
     * Finds a scrollable view by its resource ID.
     *
     * @param root The root node of the view hierarchy
     * @param resourceId The resource ID to search for (e.g., "com.app:id/recycler")
     * @return Optional containing the matching scrollable view, or empty if not found
     */
    public Optional<ViewNode> findByResourceId(ViewNode root, String resourceId) {
        return findByResourceIdRecursive(root, resourceId);
    }

    /**
     * Finds the best default scrollable view.
     *
     * <p>The "best" scrollable view is determined by:</p>
     * <ol>
     *   <li>Must be marked as scrollable</li>
     *   <li>Must have valid, non-zero bounds</li>
     *   <li>Must have area greater than minimum threshold</li>
     *   <li>Prefers the largest visible scrollable area</li>
     * </ol>
     *
     * @param root The root node of the view hierarchy
     * @return Optional containing the best scrollable view, or empty if none found
     */
    public Optional<ViewNode> findDefaultScrollable(ViewNode root) {
        List<ViewNode> scrollables = findScrollableViews(root);

        return scrollables.stream()
                .filter(this::isValidScrollable)
                .findFirst();
    }

    /**
     * Gets all resource IDs of scrollable views in the hierarchy.
     * Useful for displaying available options to the user.
     *
     * @param root The root node of the view hierarchy
     * @return List of resource IDs (excluding nulls and empty strings)
     */
    public List<String> getScrollableResourceIds(ViewNode root) {
        return findScrollableViews(root).stream()
                .map(ViewNode::getResourceId)
                .filter(id -> id != null && !id.isEmpty())
                .toList();
    }

    /**
     * Recursively collects all scrollable views from the hierarchy.
     */
    private void collectScrollableViews(ViewNode node, List<ViewNode> scrollables) {
        if (node == null) {
            return;
        }

        if (isScrollable(node)) {
            scrollables.add(node);
        }

        // Traverse children
        if (node.getChildren() != null) {
            for (ViewNode child : node.getChildren()) {
                collectScrollableViews(child, scrollables);
            }
        }
    }

    /**
     * Recursively searches for a scrollable view by resource ID.
     */
    private Optional<ViewNode> findByResourceIdRecursive(ViewNode node, String resourceId) {
        if (node == null) {
            return Optional.empty();
        }

        // Check if this node matches
        if (resourceId.equals(node.getResourceId()) && isScrollable(node)) {
            return Optional.of(node);
        }

        // Search children
        if (node.getChildren() != null) {
            for (ViewNode child : node.getChildren()) {
                Optional<ViewNode> found = findByResourceIdRecursive(child, resourceId);
                if (found.isPresent()) {
                    return found;
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if a view is marked as scrollable.
     */
    private boolean isScrollable(ViewNode node) {
        if (node.getProperties() == null) {
            return false;
        }
        Boolean scrollable = node.getProperties().get("scrollable");
        return Boolean.TRUE.equals(scrollable);
    }

    /**
     * Validates that a scrollable view has proper bounds and sufficient area.
     */
    private boolean isValidScrollable(ViewNode node) {
        ViewNode.Bounds bounds = node.getBounds();
        if (bounds == null) {
            return false;
        }

        int width = bounds.getWidth();
        int height = bounds.getHeight();

        // Must have positive dimensions
        if (width <= 0 || height <= 0) {
            return false;
        }

        // Must have sufficient area
        int area = width * height;
        return area >= MIN_SCROLLABLE_AREA;
    }

    /**
     * Calculates the area of a view's bounds.
     */
    private int getViewArea(ViewNode node) {
        ViewNode.Bounds bounds = node.getBounds();
        if (bounds == null) {
            return 0;
        }
        return bounds.getWidth() * bounds.getHeight();
    }
}
