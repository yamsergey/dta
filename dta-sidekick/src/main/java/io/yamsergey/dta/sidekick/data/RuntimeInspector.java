package io.yamsergey.dta.sidekick.data;

import android.app.Activity;
import android.os.Debug;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.view.WindowRootDiscovery;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime inspection of app state: navigation, lifecycle, memory, threads.
 * All via reflection to avoid compile-time dependencies on AndroidX Navigation etc.
 */
public class RuntimeInspector {

    private static final String TAG = "RuntimeInspector";

    // ========================================================================
    // Navigation
    // ========================================================================

    public Map<String, Object> navigationBackstack() {
        Map<String, Object> result = new HashMap<>();
        try {
            Activity activity = WindowRootDiscovery.getCurrentActivity();
            if (activity == null) {
                result.put("error", "No visible activity");
                return result;
            }

            Object navController = runOnMainThread(() -> findNavController(activity));
            if (navController == null) {
                result.put("error", "No NavController found. Fragment-based navigation (NavHostFragment) " +
                    "is supported. Compose Navigation (NavHost composable) stores the controller in " +
                    "CompositionLocals which are not accessible from outside the composition. " +
                    "Use layout_tree to see which composable screen is currently rendered.");
                return result;
            }

            // NavController.getCurrentBackStack() or iterate backQueue
            List<Map<String, Object>> entries = new ArrayList<>();
            // Try currentBackStack StateFlow first (public API, Navigation 2.7+)
            boolean gotStack = false;
            try {
                Method getCurrentBackStack = navController.getClass().getMethod("getCurrentBackStack");
                Object stateFlow = getCurrentBackStack.invoke(navController);
                if (stateFlow != null) {
                    Method getValue = stateFlow.getClass().getMethod("getValue");
                    Object list = getValue.invoke(stateFlow);
                    if (list instanceof List) {
                        for (Object entry : (List<?>) list) {
                            entries.add(backStackEntryToMap(entry));
                        }
                        gotStack = true;
                    }
                }
            } catch (Exception ignored) {}

            // Fallback: internal backQueue field
            if (!gotStack) {
                try {
                    Field backQueueField = navController.getClass().getDeclaredField("backQueue");
                    backQueueField.setAccessible(true);
                    Object backQueue = backQueueField.get(navController);
                    if (backQueue instanceof Iterable) {
                        for (Object entry : (Iterable<?>) backQueue) {
                            entries.add(backStackEntryToMap(entry));
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Last resort: just the current entry
            if (entries.isEmpty()) {
                try {
                    Method getCurrent = navController.getClass().getMethod("getCurrentBackStackEntry");
                    Object current = getCurrent.invoke(navController);
                    if (current != null) entries.add(backStackEntryToMap(current));
                } catch (Exception ignored) {}
            }

            result.put("backstack", entries);
            result.put("size", entries.size());

            // Current destination
            try {
                Method getCurrentDest = navController.getClass().getMethod("getCurrentDestination");
                Object dest = getCurrentDest.invoke(navController);
                if (dest != null) {
                    result.put("currentDestination", destinationToMap(dest));
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            result.put("error", "Failed to read backstack: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> navigationGraph() {
        Map<String, Object> result = new HashMap<>();
        try {
            Activity activity = WindowRootDiscovery.getCurrentActivity();
            if (activity == null) {
                result.put("error", "No visible activity");
                return result;
            }

            Object navController = runOnMainThread(() -> findNavController(activity));
            if (navController == null) {
                result.put("error", "No NavController found. Fragment-based navigation (NavHostFragment) " +
                    "is supported. Compose Navigation stores the controller in CompositionLocals. " +
                    "Use layout_tree to see which composable screen is currently rendered.");
                return result;
            }

            Method getGraph = navController.getClass().getMethod("getGraph");
            Object graph = getGraph.invoke(navController);
            if (graph == null) {
                result.put("error", "NavController has no graph set");
                return result;
            }

            result.put("graph", navGraphToMap(graph));

        } catch (Exception e) {
            result.put("error", "Failed to read navigation graph: " + e.getMessage());
        }
        return result;
    }

    private <T> T runOnMainThread(java.util.concurrent.Callable<T> callable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { return callable.call(); } catch (Exception e) { return null; }
        }
        final Object[] result = {null};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { result[0] = callable.call(); } catch (Exception ignored) {}
            finally { latch.countDown(); }
        });
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    private Object findNavController(Activity activity) {
        // Strategy 0: JVMTI hook captured controllers (works for both Fragment + Compose)
        java.util.List<Object> hooked = NavControllerHook.getControllers();
        if (!hooked.isEmpty()) {
            return hooked.get(hooked.size() - 1); // Most recent = active
        }

        // Strategy 1: Fragment-based — find NavHostFragment in FragmentManager
        try {
            Class<?> fragActivityClass = Class.forName("androidx.fragment.app.FragmentActivity");
            if (fragActivityClass.isInstance(activity)) {
                Method getSfm = fragActivityClass.getMethod("getSupportFragmentManager");
                Object fm = getSfm.invoke(activity);
                Method getFragments = fm.getClass().getMethod("getFragments");
                Object fragments = getFragments.invoke(fm);
                if (fragments instanceof List) {
                    Class<?> navHostClass = null;
                    try { navHostClass = Class.forName("androidx.navigation.fragment.NavHostFragment"); }
                    catch (ClassNotFoundException ignored) {}
                    if (navHostClass != null) {
                        for (Object frag : (List<?>) fragments) {
                            if (frag != null && navHostClass.isInstance(frag)) {
                                Method getNavController = navHostClass.getMethod("getNavController");
                                return getNavController.invoke(frag);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Strategy 2: Compose-based — walk the view tree looking for a view
        // tagged with NavController (Compose NavHost sets it on the ComposeView)
        try {
            android.view.View contentView = activity.findViewById(android.R.id.content);
            Object controller = findNavControllerInViewTree(contentView);
            if (controller != null) return controller;
        } catch (Exception ignored) {}

        // Strategy 3: Direct lookup via Navigation.findNavController (works for both)
        try {
            Class<?> navClass = Class.forName("androidx.navigation.Navigation");
            Method findNavController = navClass.getMethod("findNavController", Activity.class, int.class);
            return findNavController.invoke(null, activity, android.R.id.content);
        } catch (Exception ignored) {}

        return null;
    }

    private Object findNavControllerInViewTree(android.view.View view) {
        if (view == null) return null;

        // Navigation.findNavController(View) walks up the tree checking each
        // view's tag for the NavController. Compose NavHost sets this tag on
        // the ComposeView. We walk DOWN the tree trying each view.
        try {
            Class<?> navClass = Class.forName("androidx.navigation.Navigation");
            Method findFromView = navClass.getMethod("findNavController", android.view.View.class);
            return findFromView.invoke(null, view);
        } catch (Exception ignored) {}

        // Walk children — try each subtree
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Object result = findNavControllerInViewTree(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private Map<String, Object> backStackEntryToMap(Object entry) {
        Map<String, Object> map = new HashMap<>();
        try {
            Method getDest = entry.getClass().getMethod("getDestination");
            Object dest = getDest.invoke(entry);
            if (dest != null) {
                map.put("destination", destinationToMap(dest));
            }

            // Arguments
            try {
                Method getArgs = entry.getClass().getMethod("getArguments");
                Object args = getArgs.invoke(entry);
                if (args != null) {
                    Method keySet = args.getClass().getMethod("keySet");
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> keys = (java.util.Set<String>) keySet.invoke(args);
                    Map<String, Object> argsMap = new HashMap<>();
                    for (String key : keys) {
                        try {
                            Method get = args.getClass().getMethod("get", String.class);
                            Object val = get.invoke(args, key);
                            argsMap.put(key, val != null ? val.toString() : null);
                        } catch (Exception ignored) {}
                    }
                    if (!argsMap.isEmpty()) map.put("arguments", argsMap);
                }
            } catch (Exception ignored) {}

            // ID
            try {
                Method getId = entry.getClass().getMethod("getId");
                map.put("id", getId.invoke(entry).toString());
            } catch (Exception ignored) {}

        } catch (Exception e) {
            map.put("error", e.getMessage());
        }
        return map;
    }

    private Map<String, Object> destinationToMap(Object dest) {
        Map<String, Object> map = new HashMap<>();
        try {
            Method getRoute = dest.getClass().getMethod("getRoute");
            Object route = getRoute.invoke(dest);
            if (route != null) map.put("route", route.toString());
        } catch (Exception ignored) {}

        try {
            Method getLabel = dest.getClass().getMethod("getLabel");
            Object label = getLabel.invoke(dest);
            if (label != null) map.put("label", label.toString());
        } catch (Exception ignored) {}

        int destId = 0;
        try {
            Method getId = dest.getClass().getMethod("getId");
            Object id = getId.invoke(dest);
            if (id instanceof Integer) destId = (Integer) id;
            map.put("id", id);
        } catch (Exception ignored) {}

        // Resolve XML resource ID to name (for Fragment-based navigation)
        if (!map.containsKey("route") && destId != 0) {
            try {
                Activity activity = WindowRootDiscovery.getCurrentActivity();
                if (activity != null) {
                    String resName = activity.getResources().getResourceEntryName(destId);
                    map.put("route", resName);
                }
            } catch (Exception ignored) {}
        }

        try {
            Method getNavigatorName = dest.getClass().getMethod("getNavigatorName");
            map.put("navigatorName", getNavigatorName.invoke(dest));
        } catch (Exception ignored) {}

        // For fragment destinations, get the fragment class name
        try {
            Method getClassName = dest.getClass().getMethod("getClassName");
            Object className = getClassName.invoke(dest);
            if (className != null) map.put("className", className.toString());
        } catch (Exception ignored) {}

        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> navGraphToMap(Object graph) {
        Map<String, Object> map = destinationToMap(graph);
        List<Map<String, Object>> destinations = new ArrayList<>();
        try {
            // NavGraph implements Iterable<NavDestination>
            if (graph instanceof Iterable) {
                for (Object dest : (Iterable<?>) graph) {
                    // Recurse for nested graphs
                    try {
                        Class<?> navGraphClass = Class.forName("androidx.navigation.NavGraph");
                        if (navGraphClass.isInstance(dest)) {
                            destinations.add(navGraphToMap(dest));
                            continue;
                        }
                    } catch (Exception ignored) {}
                    destinations.add(destinationToMap(dest));
                }
            }
        } catch (Exception e) {
            map.put("iterationError", e.getMessage());
        }
        map.put("destinations", destinations);
        map.put("destinationCount", destinations.size());
        return map;
    }

    // ========================================================================
    // Memory
    // ========================================================================

    public Map<String, Object> memory() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> result = new HashMap<>();
        result.put("heapMax", rt.maxMemory());
        result.put("heapTotal", rt.totalMemory());
        result.put("heapFree", rt.freeMemory());
        result.put("heapUsed", rt.totalMemory() - rt.freeMemory());
        result.put("nativeHeap", Debug.getNativeHeapSize());
        result.put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize());
        result.put("nativeHeapFree", Debug.getNativeHeapFreeSize());
        return result;
    }

    // ========================================================================
    // Threads
    // ========================================================================

    public Map<String, Object> threads(boolean includeStackTraces) {
        Map<String, Object> result = new HashMap<>();
        Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
        List<Map<String, Object>> threadList = new ArrayList<>();

        for (Map.Entry<Thread, StackTraceElement[]> entry : allThreads.entrySet()) {
            Thread t = entry.getKey();
            Map<String, Object> info = new HashMap<>();
            info.put("name", t.getName());
            info.put("state", t.getState().name());
            info.put("daemon", t.isDaemon());
            info.put("priority", t.getPriority());
            ThreadGroup group = t.getThreadGroup();
            if (group != null) info.put("group", group.getName());

            if (includeStackTraces) {
                StackTraceElement[] stack = entry.getValue();
                List<String> frames = new ArrayList<>();
                for (StackTraceElement frame : stack) {
                    frames.add(frame.toString());
                }
                info.put("stackTrace", frames);
            }

            threadList.add(info);
        }

        result.put("count", threadList.size());
        result.put("threads", threadList);
        return result;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @SuppressWarnings("unchecked")
    public Map<String, Object> lifecycle() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> activities = new ArrayList<>();

        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = atClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesMap = activitiesField.get(at);

            if (activitiesMap instanceof Map) {
                for (Object record : ((Map<?, ?>) activitiesMap).values()) {
                    Map<String, Object> info = new HashMap<>();
                    try {
                        Field actField = record.getClass().getDeclaredField("activity");
                        actField.setAccessible(true);
                        Activity act = (Activity) actField.get(record);
                        if (act == null) continue;

                        info.put("className", act.getClass().getName());
                        info.put("taskId", act.getTaskId());

                        Field pausedField = record.getClass().getDeclaredField("paused");
                        pausedField.setAccessible(true);
                        boolean paused = pausedField.getBoolean(record);

                        Field stoppedField = record.getClass().getDeclaredField("stopped");
                        stoppedField.setAccessible(true);
                        boolean stopped = stoppedField.getBoolean(record);

                        String state = stopped ? "STOPPED" : paused ? "PAUSED" : "RESUMED";
                        info.put("state", state);

                        activities.add(info);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            result.put("error", "Failed to enumerate activities: " + e.getMessage());
        }

        result.put("activities", activities);
        result.put("count", activities.size());
        return result;
    }
}
