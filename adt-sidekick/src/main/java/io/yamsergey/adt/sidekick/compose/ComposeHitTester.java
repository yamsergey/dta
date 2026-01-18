package io.yamsergey.adt.sidekick.compose;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import io.yamsergey.adt.sidekick.SidekickLog;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility for hit-testing Compose UI elements and capturing screenshots.
 */
public class ComposeHitTester {

    private static final String TAG = "ComposeHitTester";

    /**
     * Result of a hit test operation.
     */
    public static class HitResult {
        public final boolean found;
        public final Map<String, Object> element;
        public final List<String> ancestors;
        public final List<Map<String, Object>> ancestorNodes;

        public HitResult(boolean found, Map<String, Object> element,
                        List<String> ancestors, List<Map<String, Object>> ancestorNodes) {
            this.found = found;
            this.element = element;
            this.ancestors = ancestors;
            this.ancestorNodes = ancestorNodes;
        }

        public static HitResult notFound() {
            return new HitResult(false, null, null, null);
        }
    }

    /**
     * Performs hit testing on the compose tree to find the element at given coordinates.
     *
     * @param x X coordinate in screen pixels
     * @param y Y coordinate in screen pixels
     * @return HitResult containing the deepest element at that position
     */
    public static HitResult hitTest(int x, int y) {
        // Get the unified tree first
        Map<String, Object> tree = ComposeInspector.captureUnifiedTree();
        if (tree == null) {
            return HitResult.notFound();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) tree.get("root");
        if (root == null) {
            return HitResult.notFound();
        }

        // Track ancestors as we descend
        List<String> ancestors = new ArrayList<>();
        List<Map<String, Object>> ancestorNodes = new ArrayList<>();

        // Find deepest node containing the point
        Map<String, Object> result = findDeepestNode(root, x, y, ancestors, ancestorNodes);

        if (result != null) {
            // Add ancestor info to result
            result.put("ancestors", new ArrayList<>(ancestors));

            // Get children names for context
            List<String> childNames = getChildNames(result);
            if (!childNames.isEmpty()) {
                result.put("childNames", childNames);
            }

            return new HitResult(true, result, ancestors, ancestorNodes);
        }

        return HitResult.notFound();
    }

    /**
     * Performs hit testing and returns all elements at the given coordinates,
     * from root to deepest (for multi-select/layer inspection).
     */
    public static List<Map<String, Object>> hitTestAll(int x, int y) {
        Map<String, Object> tree = ComposeInspector.captureUnifiedTree();
        if (tree == null) {
            return new ArrayList<>();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) tree.get("root");
        if (root == null) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> hits = new ArrayList<>();
        collectAllHits(root, x, y, hits);
        return hits;
    }

    /**
     * Recursively finds the deepest node containing the given point.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findDeepestNode(Map<String, Object> node, int x, int y,
                                                        List<String> ancestors,
                                                        List<Map<String, Object>> ancestorNodes) {
        if (node == null) return null;

        // Check if point is within this node's bounds
        Map<String, Object> bounds = (Map<String, Object>) node.get("bounds");

        // If bounds is null (like synthetic root), still check children
        // If bounds exists but doesn't contain point, skip this branch
        if (bounds != null && !containsPoint(bounds, x, y)) {
            return null;
        }

        // This node contains the point (or has no bounds) - check children for a deeper match
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");

        if (children != null && !children.isEmpty()) {
            // Add current node to ancestors before checking children
            String composable = (String) node.get("composable");
            if (composable != null) {
                ancestors.add(composable);
                ancestorNodes.add(createNodeSummary(node));
            }

            // Check children (in reverse order - later children are drawn on top)
            for (int i = children.size() - 1; i >= 0; i--) {
                Map<String, Object> child = children.get(i);
                Map<String, Object> result = findDeepestNode(child, x, y, ancestors, ancestorNodes);
                if (result != null) {
                    return result;
                }
            }

            // No child contains the point, remove from ancestors and return this node
            if (composable != null) {
                ancestors.remove(ancestors.size() - 1);
                ancestorNodes.remove(ancestorNodes.size() - 1);
            }
        }

        // This is the deepest node containing the point
        return node;
    }

    /**
     * Collects all nodes containing the given point (from root to leaf).
     */
    @SuppressWarnings("unchecked")
    private static void collectAllHits(Map<String, Object> node, int x, int y,
                                       List<Map<String, Object>> hits) {
        if (node == null) return;

        Map<String, Object> bounds = (Map<String, Object>) node.get("bounds");

        // If bounds exists but doesn't contain point, skip this branch
        if (bounds != null && !containsPoint(bounds, x, y)) {
            return;
        }

        // This node contains the point (or has no bounds like synthetic root)
        // Only add to hits if it has bounds (skip synthetic containers)
        if (bounds != null) {
            hits.add(createNodeSummary(node));
        }

        // Check children
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        if (children != null) {
            for (Map<String, Object> child : children) {
                collectAllHits(child, x, y, hits);
            }
        }
    }

    /**
     * Creates a summary of a node (without children, for cleaner output).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> createNodeSummary(Map<String, Object> node) {
        Map<String, Object> summary = new HashMap<>();

        // Copy relevant fields
        String[] fields = {"composable", "className", "bounds", "text", "role",
                          "testTag", "contentDescription", "semanticsId",
                          "sourceFile", "lineNumber", "offset", "packageHash"};

        for (String field : fields) {
            Object value = node.get(field);
            if (value != null) {
                summary.put(field, value);
            }
        }

        // Generate an ID if not present
        if (!summary.containsKey("id")) {
            summary.put("id", System.identityHashCode(node));
        }

        return summary;
    }

    /**
     * Gets the names of child composables.
     */
    @SuppressWarnings("unchecked")
    private static List<String> getChildNames(Map<String, Object> node) {
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");

        if (children != null) {
            for (Map<String, Object> child : children) {
                String name = (String) child.get("composable");
                if (name != null) {
                    names.add(name);
                }
            }
        }

        return names;
    }

    /**
     * Checks if a point is within bounds.
     */
    private static boolean containsPoint(Map<String, Object> bounds, int x, int y) {
        int left = getInt(bounds, "left", 0);
        int top = getInt(bounds, "top", 0);
        int right = getInt(bounds, "right", 0);
        int bottom = getInt(bounds, "bottom", 0);

        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Captures a screenshot of the current activity's window.
     *
     * @param window The window to capture
     * @return PNG bytes, or null on failure
     */
    public static byte[] captureScreenshot(Window window) {
        if (window == null) {
            SidekickLog.e(TAG, "Window is null");
            return null;
        }

        View decorView = window.getDecorView();
        if (decorView == null) {
            SidekickLog.e(TAG, "DecorView is null");
            return null;
        }

        int width = decorView.getWidth();
        int height = decorView.getHeight();

        if (width <= 0 || height <= 0) {
            SidekickLog.e(TAG, "Invalid view dimensions: " + width + "x" + height);
            return null;
        }

        // Try PixelCopy for hardware-accelerated views (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            byte[] result = captureWithPixelCopy(window, width, height);
            if (result != null) {
                return result;
            }
            // Fall through to canvas method
        }

        // Fallback to canvas drawing
        return captureWithCanvas(decorView, width, height);
    }

    /**
     * Captures screenshot using PixelCopy API (API 26+).
     */
    private static byte[] captureWithPixelCopy(Window window, int width, int height) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }

        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Integer> resultCode = new AtomicReference<>(PixelCopy.ERROR_UNKNOWN);

            Handler handler = new Handler(Looper.getMainLooper());
            PixelCopy.request(window, bitmap, result -> {
                resultCode.set(result);
                latch.countDown();
            }, handler);

            if (!latch.await(2, TimeUnit.SECONDS)) {
                SidekickLog.e(TAG, "PixelCopy timeout");
                bitmap.recycle();
                return null;
            }

            if (resultCode.get() != PixelCopy.SUCCESS) {
                SidekickLog.e(TAG, "PixelCopy failed with code: " + resultCode.get());
                bitmap.recycle();
                return null;
            }

            byte[] pngBytes = bitmapToPng(bitmap);
            bitmap.recycle();
            return pngBytes;

        } catch (Exception e) {
            SidekickLog.e(TAG, "PixelCopy error", e);
            return null;
        }
    }

    /**
     * Captures screenshot using Canvas drawing (fallback method).
     */
    private static byte[] captureWithCanvas(View view, int width, int height) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);

            byte[] pngBytes = bitmapToPng(bitmap);
            bitmap.recycle();
            return pngBytes;

        } catch (Exception e) {
            SidekickLog.e(TAG, "Canvas capture error", e);
            return null;
        }
    }

    /**
     * Converts a Bitmap to PNG byte array.
     */
    private static byte[] bitmapToPng(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }

    /**
     * Finds element by its unique ID in the tree.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> findById(String id) {
        Map<String, Object> tree = ComposeInspector.captureUnifiedTree();
        if (tree == null) return null;

        Map<String, Object> root = (Map<String, Object>) tree.get("root");
        return findByIdRecursive(root, id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findByIdRecursive(Map<String, Object> node, String targetId) {
        if (node == null) return null;

        Object nodeId = node.get("id");
        if (nodeId != null && targetId.equals(String.valueOf(nodeId))) {
            return node;
        }

        // Also check identity hash
        if (targetId.equals(String.valueOf(System.identityHashCode(node)))) {
            return node;
        }

        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        if (children != null) {
            for (Map<String, Object> child : children) {
                Map<String, Object> result = findByIdRecursive(child, targetId);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }
}
