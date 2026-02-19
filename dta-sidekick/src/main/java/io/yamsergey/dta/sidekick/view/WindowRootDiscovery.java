package io.yamsergey.dta.sidekick.view;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import io.yamsergey.dta.sidekick.SidekickLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers all window root views in the current application process.
 *
 * <p>Uses WindowInspector (API 29+) as primary method with WindowManagerGlobal
 * reflection fallback. Covers app windows, dialogs, popups, and toasts.</p>
 *
 * <p>Reference: Android Studio's RootsDetector.getAndroidViews()</p>
 */
public class WindowRootDiscovery {

    private static final String TAG = "WindowRootDiscovery";

    /**
     * Represents a discovered window root.
     */
    public static class WindowRoot {
        public final View rootView;
        public final long drawingId;
        public final String title;
        public final String windowType;
        public final int zOrder;

        public WindowRoot(View rootView, long drawingId, String title, String windowType, int zOrder) {
            this.rootView = rootView;
            this.drawingId = drawingId;
            this.title = title;
            this.windowType = windowType;
            this.zOrder = zOrder;
        }
    }

    /**
     * Gets all visible window roots, sorted by z-order.
     * Must be called from the main thread.
     *
     * @return list of WindowRoot objects, or empty list if none found
     */
    public static List<WindowRoot> getRoots() {
        List<View> rootViews = getAllWindowRootViews();
        List<WindowRoot> roots = new ArrayList<>();

        for (int i = 0; i < rootViews.size(); i++) {
            View view = rootViews.get(i);
            long drawingId = getUniqueDrawingId(view);
            String title = inferWindowTitle(view);
            String windowType = inferWindowType(view);
            roots.add(new WindowRoot(view, drawingId, title, windowType, i));
        }

        SidekickLog.d(TAG, "Discovered " + roots.size() + " window roots");
        return roots;
    }

    /**
     * Gets all root views using the best available API.
     * Uses WindowInspector (API 29+) as primary, WindowManagerGlobal as fallback.
     * Filters to visible, attached views sorted by z-order.
     */
    @SuppressWarnings("unchecked")
    private static List<View> getAllWindowRootViews() {
        List<View> rootViews = new ArrayList<>();

        // Try WindowInspector first (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                rootViews = android.view.inspector.WindowInspector.getGlobalWindowViews();
                SidekickLog.d(TAG, "WindowInspector returned " + rootViews.size() + " root views");
            } catch (Exception e) {
                SidekickLog.w(TAG, "WindowInspector failed: " + e.getMessage());
                rootViews = new ArrayList<>();
            }
        }

        // Fallback to WindowManagerGlobal reflection
        if (rootViews.isEmpty()) {
            try {
                Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
                Method getInstance = wmgClass.getDeclaredMethod("getInstance");
                Object wmg = getInstance.invoke(null);

                Field viewsField = wmgClass.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object viewsObj = viewsField.get(wmg);

                if (viewsObj instanceof List) {
                    for (Object v : (List<?>) viewsObj) {
                        if (v instanceof View) {
                            rootViews.add((View) v);
                        }
                    }
                }
                SidekickLog.d(TAG, "WindowManagerGlobal returned " + rootViews.size() + " root views");
            } catch (Exception e) {
                SidekickLog.w(TAG, "WindowManagerGlobal fallback failed: " + e.getMessage());
            }
        }

        // Filter to visible, attached views
        List<View> filtered = new ArrayList<>();
        for (View view : rootViews) {
            if (view.getVisibility() == View.VISIBLE && view.isAttachedToWindow()) {
                filtered.add(view);
            }
        }

        // Sort by z-order
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            filtered.sort((v1, v2) -> Float.compare(v1.getZ(), v2.getZ()));
        }

        return filtered;
    }

    /**
     * Gets the unique drawing ID for a view (API 29+).
     */
    private static long getUniqueDrawingId(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return view.getUniqueDrawingId();
            } catch (Exception e) {
                // Fall through
            }
        }
        return System.identityHashCode(view);
    }

    /**
     * Infers the window title from the root view.
     */
    private static String inferWindowTitle(View view) {
        // Try to get window title from LayoutParams
        try {
            android.view.WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) view.getLayoutParams();
            if (lp != null && lp.getTitle() != null) {
                String title = lp.getTitle().toString();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        // Try to get activity name from context
        try {
            android.content.Context ctx = view.getContext();
            if (ctx instanceof Activity) {
                return ((Activity) ctx).getClass().getSimpleName();
            }
        } catch (Exception e) {
            // Ignore
        }

        return view.getClass().getSimpleName();
    }

    /**
     * Infers the window type from the root view.
     */
    private static String inferWindowType(View view) {
        try {
            android.view.WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) view.getLayoutParams();
            if (lp != null) {
                int type = lp.type;
                if (type >= android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    && type <= android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) {
                    return "application";
                } else if (type >= android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    && type <= android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW) {
                    return "sub_window";
                } else if (type >= android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                    && type <= android.view.WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
                    return "system";
                }
                // Specific types
                if (type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                    return "popup";
                }
                if (type == android.view.WindowManager.LayoutParams.TYPE_TOAST) {
                    return "toast";
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }

    /**
     * Converts a WindowRoot to a Map for JSON serialization.
     */
    public static Map<String, Object> toMap(WindowRoot root) {
        Map<String, Object> map = new HashMap<>();
        map.put("title", root.title);
        map.put("type", root.windowType);
        map.put("rootId", root.drawingId);
        map.put("zOrder", root.zOrder);
        return map;
    }
}
