package io.yamsergey.dta.sidekick.layout;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.compose.ComposeInspector;
import io.yamsergey.dta.sidekick.view.ViewPropertyExtractor;
import io.yamsergey.dta.sidekick.view.ViewTreeCapture;
import io.yamsergey.dta.sidekick.view.WindowRootDiscovery;
import io.yamsergey.dta.sidekick.view.WindowRootDiscovery.WindowRoot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a unified layout tree merging Android View hierarchy with Jetpack Compose subtrees.
 *
 * <p>Orchestrates the full capture:</p>
 * <ol>
 *   <li>Call WindowRootDiscovery to find all window roots</li>
 *   <li>For each root, walk the View tree via ViewTreeCapture</li>
 *   <li>Find AndroidComposeView nodes (marked with isComposeView)</li>
 *   <li>For each ComposeView, capture the Compose subtree and inline it</li>
 *   <li>Return the unified tree as a Map for JSON serialization</li>
 * </ol>
 *
 * <p>Must be called from the main thread.</p>
 */
public class UnifiedTreeBuilder {

    private static final String TAG = "UnifiedTreeBuilder";

    /**
     * Captures the complete unified layout hierarchy across all windows.
     *
     * @return Map representing the unified tree, suitable for JSON serialization
     */
    public static Map<String, Object> capture() {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "layout_tree");
        result.put("timestamp", System.currentTimeMillis());

        // Screen info
        Activity activity = getCurrentActivity();
        if (activity != null) {
            Map<String, Object> screen = new HashMap<>();
            screen.put("activity", activity.getClass().getSimpleName());

            Window window = activity.getWindow();
            if (window != null && window.getDecorView() != null) {
                View decorView = window.getDecorView();
                screen.put("windowWidth", decorView.getWidth());
                screen.put("windowHeight", decorView.getHeight());
            }

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            screen.put("screenWidth", dm.widthPixels);
            screen.put("screenHeight", dm.heightPixels);
            screen.put("density", dm.density);

            result.put("screen", screen);
        }

        // Discover all window roots
        List<WindowRoot> roots = WindowRootDiscovery.getRoots();
        if (roots.isEmpty()) {
            SidekickLog.w(TAG, "No window roots found");
            result.put("windows", new ArrayList<>());
            return result;
        }

        List<Map<String, Object>> windows = new ArrayList<>();

        for (WindowRoot windowRoot : roots) {
            Map<String, Object> windowMap = WindowRootDiscovery.toMap(windowRoot);

            // Capture the View tree for this window
            Map<String, Object> viewTree = ViewTreeCapture.captureTree(windowRoot.rootView);
            if (viewTree != null) {
                // Find and replace ComposeView children with Compose subtrees
                inlineComposeSubtrees(viewTree, windowRoot.rootView);
                windowMap.put("tree", viewTree);
            }

            windows.add(windowMap);
        }

        result.put("windows", windows);
        result.put("windowCount", windows.size());

        return result;
    }

    /**
     * Captures detailed properties for a single view by its drawing ID.
     *
     * @param drawingId the unique drawing ID of the target view
     * @return property map, or null if view not found
     */
    public static Map<String, Object> captureProperties(long drawingId) {
        List<WindowRoot> roots = WindowRootDiscovery.getRoots();

        for (WindowRoot root : roots) {
            View target = ViewPropertyExtractor.findViewByDrawingId(root.rootView, drawingId);
            if (target != null) {
                return ViewPropertyExtractor.extract(target);
            }
        }

        SidekickLog.w(TAG, "View not found with drawingId: " + drawingId);
        return null;
    }

    /**
     * Walks the captured view tree map and replaces ComposeView children
     * with actual Compose subtrees.
     */
    @SuppressWarnings("unchecked")
    private static void inlineComposeSubtrees(Map<String, Object> node, View rootView) {
        if (node == null) return;

        // If this node is a ComposeView, replace its children with Compose subtree
        Boolean isComposeView = (Boolean) node.get("isComposeView");
        if (Boolean.TRUE.equals(isComposeView)) {
            Object drawingIdObj = node.get("drawingId");
            if (drawingIdObj != null) {
                long drawingId = ((Number) drawingIdObj).longValue();
                View composeView = ViewPropertyExtractor.findViewByDrawingId(rootView, drawingId);
                if (composeView != null) {
                    Map<String, Object> composeTree = captureComposeSubtree(composeView);
                    if (composeTree != null) {
                        Object children = composeTree.get("children");
                        if (children != null) {
                            node.put("children", children);
                            // Attach hosted Views (e.g., WebViews) to their AndroidView compose nodes
                            inlineHostedViews(children, composeView);
                        }
                        // Copy compose metadata to the node
                        if (composeTree.containsKey("composeRootInfo")) {
                            node.put("composeRootInfo", composeTree.get("composeRootInfo"));
                        }
                    }
                }
            }
            return; // Don't recurse into compose children from view side
        }

        // Recurse into children
        Object childrenObj = node.get("children");
        if (childrenObj instanceof List) {
            for (Object child : (List<?>) childrenObj) {
                if (child instanceof Map) {
                    inlineComposeSubtrees((Map<String, Object>) child, rootView);
                }
            }
        }
    }

    /**
     * Walks the inlined Compose tree and attaches hosted Views to AndroidView nodes.
     *
     * <p>When Compose's {@code AndroidView} composable hosts a real View (e.g., WebView),
     * the Compose tree only shows "AndroidView" as a leaf. The actual View lives inside
     * the {@code AndroidViewsHandler} child of AndroidComposeView. This method finds
     * those hosted Views and attaches them as children of their corresponding
     * AndroidView compose nodes.</p>
     */
    @SuppressWarnings("unchecked")
    private static void inlineHostedViews(Object composeChildren, View composeView) {
        if (!(composeView instanceof ViewGroup) || !(composeChildren instanceof List)) return;

        ViewGroup composeViewGroup = (ViewGroup) composeView;
        int childCount = composeViewGroup.getChildCount();
        // Compose hosts AndroidView children inside an AndroidViewsHandler, which
        // is a direct child of AndroidComposeView. Find it and collect its children.
        List<View> hostedViews = new ArrayList<>();
        for (int i = 0; i < childCount; i++) {
            View child = composeViewGroup.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            String childClass = child.getClass().getName();
            if (childClass.contains("AndroidViewsHandler") && child instanceof ViewGroup) {
                // The handler contains the actual hosted Views (WebViews, etc.)
                ViewGroup handler = (ViewGroup) child;
                for (int j = 0; j < handler.getChildCount(); j++) {
                    View hosted = handler.getChildAt(j);
                    if (hosted.getVisibility() != View.GONE) {
                        hostedViews.add(hosted);
                    }
                }
            }
        }

        if (hostedViews.isEmpty()) return;

        // Find AndroidView compose nodes and attach the hosted Views
        List<Map<String, Object>> androidViewNodes = new ArrayList<>();
        collectAndroidViewNodes((List<Object>) composeChildren, androidViewNodes);

        // Match by order (Compose maintains child ordering).
        int pairCount = Math.min(androidViewNodes.size(), hostedViews.size());
        for (int i = 0; i < pairCount; i++) {
            Map<String, Object> androidViewNode = androidViewNodes.get(i);
            View hostedView = hostedViews.get(i);

            Map<String, Object> viewCapture = ViewTreeCapture.captureTree(hostedView);
            if (viewCapture != null) {
                List<Map<String, Object>> children = new ArrayList<>();
                children.add(viewCapture);
                androidViewNode.put("children", children);
            }
        }
    }

    /**
     * Recursively collects compose nodes with composable name "AndroidView".
     */
    @SuppressWarnings("unchecked")
    private static void collectAndroidViewNodes(List<Object> nodes, List<Map<String, Object>> results) {
        for (Object node : nodes) {
            if (!(node instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) node;

            String composable = (String) map.get("composable");
            if ("AndroidView".equals(composable)) {
                results.add(map);
            }

            // Recurse into children
            Object children = map.get("children");
            if (children instanceof List) {
                collectAndroidViewNodes((List<Object>) children, results);
            }
        }
    }

    /**
     * Captures the Compose subtree for a given AndroidComposeView.
     * Delegates to ComposeInspector for the actual Compose tree walking.
     */
    private static Map<String, Object> captureComposeSubtree(View composeView) {
        try {
            return ComposeInspector.captureComposeSubtree(composeView);
        } catch (Exception e) {
            SidekickLog.e(TAG, "Error capturing Compose subtree", e);
            return null;
        }
    }

    /**
     * Gets the current resumed Activity.
     * Delegates to WindowRootDiscovery to avoid duplication.
     */
    private static Activity getCurrentActivity() {
        return WindowRootDiscovery.getCurrentActivity();
    }
}
