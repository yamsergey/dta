package io.yamsergey.dta.sidekick.view;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import io.yamsergey.dta.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive view tree walker that captures rich properties per View node.
 *
 * <p>Captures identity, geometry (including transforms), drawing properties,
 * content (text, hints), state flags, and layout parameters. Designed to
 * provide Android Studio Layout Inspector-level information depth.</p>
 *
 * <p>Must be called from the main thread.</p>
 */
public class ViewTreeCapture {

    private static final String TAG = "ViewTreeCapture";
    private static final int MAX_DEPTH = 100;

    // AndroidComposeView class cached for detection
    private static volatile Class<?> androidComposeViewClass;
    private static volatile boolean composeClassResolved = false;

    // WebView class cached for detection
    private static volatile Class<?> webViewClass;
    private static volatile boolean webViewClassResolved = false;

    /**
     * Captures the full view tree starting from the given root.
     *
     * @param rootView the root View to capture from
     * @return Map representing the view tree, suitable for JSON serialization
     */
    public static Map<String, Object> captureTree(View rootView) {
        if (rootView == null) return null;
        return captureNode(rootView, 0);
    }

    /**
     * Captures a single View node and its children recursively.
     */
    private static Map<String, Object> captureNode(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) return null;

        // Skip GONE views and their entire subtree. This is important for hidden
        // fragment ComposeViews: when Fragment.hide() is called, the fragment's root
        // view becomes GONE but stays in the hierarchy. Without this check, we'd
        // capture stale Compose trees from back-stacked fragments.
        //
        // Note: Android Studio's Layout Inspector includes GONE views in the tree
        // (see ViewExtensions.kt flatten/toNodeImpl) and lets the desktop filter them.
        // We intentionally deviate here — our output goes directly to AI assistants
        // via MCP where including invisible content would be confusing and wasteful.
        if (view.getVisibility() == View.GONE) return null;

        Map<String, Object> node = new HashMap<>();
        Resources resources = view.getResources();

        // Node type marker
        node.put("nodeType", "view");

        // === Identity ===
        long drawingId = getUniqueDrawingId(view);
        node.put("drawingId", drawingId);
        node.put("className", view.getClass().getName());

        // Resource ID
        int viewId = view.getId();
        if (viewId != View.NO_ID) {
            String resourceName = ResourceResolver.resolveResourceName(resources, viewId);
            if (resourceName != null) {
                node.put("resourceId", resourceName);
            }
            node.put("viewIdInt", viewId);
        }

        // === Geometry ===
        int[] screenLoc = new int[2];
        view.getLocationOnScreen(screenLoc);
        int left = screenLoc[0];
        int top = screenLoc[1];
        int width = view.getWidth();
        int height = view.getHeight();

        Map<String, Object> bounds = new HashMap<>();
        bounds.put("left", left);
        bounds.put("top", top);
        bounds.put("right", left + width);
        bounds.put("bottom", top + height);
        bounds.put("width", width);
        bounds.put("height", height);
        node.put("bounds", bounds);

        // Transformed bounds (if view has non-identity transform)
        Matrix matrix = view.getMatrix();
        if (matrix != null && !matrix.isIdentity()) {
            float[] corners = new float[]{
                0, 0,
                width, 0,
                width, height,
                0, height
            };
            matrix.mapPoints(corners);
            // Offset to screen coordinates using view's parent location
            int parentLeft = left - view.getLeft();
            int parentTop = top - view.getTop();
            Map<String, Object> renderBounds = new HashMap<>();
            List<Map<String, Object>> quad = new ArrayList<>();
            for (int i = 0; i < 8; i += 2) {
                Map<String, Object> point = new HashMap<>();
                point.put("x", Math.round(corners[i] + parentLeft));
                point.put("y", Math.round(corners[i + 1] + parentTop));
                quad.add(point);
            }
            renderBounds.put("quad", quad);
            node.put("renderBounds", renderBounds);
        }

        // Layout params
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            Map<String, Object> layoutParams = new HashMap<>();
            layoutParams.put("width", layoutSpecToString(lp.width));
            layoutParams.put("height", layoutSpecToString(lp.height));

            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                Map<String, Object> margins = new HashMap<>();
                margins.put("left", mlp.leftMargin);
                margins.put("top", mlp.topMargin);
                margins.put("right", mlp.rightMargin);
                margins.put("bottom", mlp.bottomMargin);
                layoutParams.put("margins", margins);
            }
            node.put("layoutParams", layoutParams);
        }

        // Padding
        Map<String, Object> padding = new HashMap<>();
        padding.put("left", view.getPaddingLeft());
        padding.put("top", view.getPaddingTop());
        padding.put("right", view.getPaddingRight());
        padding.put("bottom", view.getPaddingBottom());
        node.put("padding", padding);

        // === Drawing ===
        node.put("alpha", view.getAlpha());
        node.put("elevation", view.getElevation());
        if (view.getTranslationX() != 0) node.put("translationX", view.getTranslationX());
        if (view.getTranslationY() != 0) node.put("translationY", view.getTranslationY());
        if (view.getTranslationZ() != 0) node.put("translationZ", view.getTranslationZ());
        if (view.getRotation() != 0) node.put("rotation", view.getRotation());
        if (view.getRotationX() != 0) node.put("rotationX", view.getRotationX());
        if (view.getRotationY() != 0) node.put("rotationY", view.getRotationY());
        if (view.getScaleX() != 1) node.put("scaleX", view.getScaleX());
        if (view.getScaleY() != 1) node.put("scaleY", view.getScaleY());

        // Visibility
        int visibility = view.getVisibility();
        node.put("visibility", visibility == View.VISIBLE ? "VISIBLE"
            : visibility == View.INVISIBLE ? "INVISIBLE" : "GONE");

        // Background
        if (view.getBackground() instanceof ColorDrawable) {
            int color = ((ColorDrawable) view.getBackground()).getColor();
            node.put("backgroundColor", ResourceResolver.colorToHex(color));
        }

        // === Content ===
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0) {
                node.put("text", text.toString());
            }
            if (view instanceof EditText) {
                CharSequence hint = tv.getHint();
                if (hint != null && hint.length() > 0) {
                    node.put("hint", hint.toString());
                }
            }
        }

        CharSequence contentDesc = view.getContentDescription();
        if (contentDesc != null && contentDesc.length() > 0) {
            node.put("contentDescription", contentDesc.toString());
        }

        Object tag = view.getTag();
        if (tag instanceof String) {
            node.put("tag", tag.toString());
        }

        // === State ===
        if (view.isClickable()) node.put("isClickable", true);
        if (view.isFocusable()) node.put("isFocusable", true);
        if (!view.isEnabled()) node.put("isEnabled", false);
        if (view.isSelected()) node.put("isSelected", true);
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)
            || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)) {
            node.put("isScrollable", true);
        }

        // === Compose View detection ===
        boolean isComposeView = isAndroidComposeView(view);
        if (isComposeView) {
            node.put("isComposeView", true);
        }

        // === WebView detection ===
        boolean isWebView = isWebView(view);
        if (isWebView) {
            node.put("isWebView", true);
            // Extract current URL via reflection
            try {
                Object url = view.getClass().getMethod("getUrl").invoke(view);
                if (url != null) {
                    node.put("webViewUrl", url.toString());
                }
            } catch (Exception e) {
                SidekickLog.d(TAG, "Could not extract WebView URL: " + e.getMessage());
            }
        }

        // === Children ===
        // Skip internal children for Compose views (handled by Compose inspector)
        // and WebViews (opaque container — web content injected by host-side CDP)
        if (view instanceof ViewGroup && !isComposeView && !isWebView) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            if (childCount > 0) {
                List<Map<String, Object>> children = new ArrayList<>();
                for (int i = 0; i < childCount; i++) {
                    Map<String, Object> child = captureNode(group.getChildAt(i), depth + 1);
                    if (child != null) {
                        children.add(child);
                    }
                }
                if (!children.isEmpty()) {
                    node.put("children", children);
                }
            }
        }

        return node;
    }

    /**
     * Checks if a view is an AndroidComposeView.
     */
    private static boolean isAndroidComposeView(View view) {
        resolveComposeClass();
        return androidComposeViewClass != null && androidComposeViewClass.isInstance(view);
    }

    /**
     * Returns the AndroidComposeView class, or null if Compose is not available.
     */
    public static Class<?> getAndroidComposeViewClass() {
        resolveComposeClass();
        return androidComposeViewClass;
    }

    private static void resolveComposeClass() {
        if (!composeClassResolved) {
            composeClassResolved = true;
            try {
                androidComposeViewClass = Class.forName(
                    "androidx.compose.ui.platform.AndroidComposeView");
            } catch (ClassNotFoundException e) {
                SidekickLog.d(TAG, "AndroidComposeView not found - app may not use Compose");
            }
        }
    }

    /**
     * Checks if a view is a WebView.
     */
    private static boolean isWebView(View view) {
        resolveWebViewClass();
        return webViewClass != null && webViewClass.isInstance(view);
    }

    /**
     * Public check for whether a View is a WebView instance.
     * Used by UnifiedTreeBuilder to detect WebViews hosted inside Compose AndroidView.
     */
    public static boolean isWebViewInstance(View view) {
        return isWebView(view);
    }

    private static void resolveWebViewClass() {
        if (!webViewClassResolved) {
            webViewClassResolved = true;
            try {
                webViewClass = Class.forName("android.webkit.WebView");
            } catch (ClassNotFoundException e) {
                SidekickLog.d(TAG, "WebView class not found");
            }
        }
    }

    /**
     * Converts a layout dimension spec to a human-readable string.
     */
    private static String layoutSpecToString(int spec) {
        if (spec == ViewGroup.LayoutParams.MATCH_PARENT) return "match_parent";
        if (spec == ViewGroup.LayoutParams.WRAP_CONTENT) return "wrap_content";
        return spec + "px";
    }

    /**
     * Gets the unique drawing ID (API 29+) or falls back to identity hash code.
     * Delegates to ViewPropertyExtractor to avoid duplication.
     */
    private static long getUniqueDrawingId(View view) {
        return ViewPropertyExtractor.getUniqueDrawingId(view);
    }
}
