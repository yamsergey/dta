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
import java.util.Set;

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

        // Declared arguments (name, type, default, nullable)
        try {
            Method getArguments = dest.getClass().getMethod("getArguments");
            @SuppressWarnings("unchecked")
            Map<String, Object> argsMap = (Map<String, Object>) getArguments.invoke(dest);
            if (argsMap != null && !argsMap.isEmpty()) {
                List<Map<String, Object>> argsList = new ArrayList<>();
                for (Map.Entry<String, Object> argEntry : argsMap.entrySet()) {
                    Map<String, Object> argInfo = new HashMap<>();
                    argInfo.put("name", argEntry.getKey());
                    Object navArg = argEntry.getValue();
                    if (navArg != null) {
                        try {
                            Method getType = navArg.getClass().getMethod("getType");
                            Object type = getType.invoke(navArg);
                            if (type != null) {
                                // NavType.getName() returns "string", "integer", etc.
                                String typeName = null;
                                try {
                                    Method getName = type.getClass().getMethod("getName");
                                    typeName = (String) getName.invoke(type);
                                } catch (Exception ignored3) {}
                                if (typeName == null || typeName.isEmpty()) {
                                    typeName = type.toString();
                                }
                                argInfo.put("type", typeName);
                            }
                        } catch (Exception ignored2) {}
                        try {
                            Method isNullable = navArg.getClass().getMethod("isNullable");
                            argInfo.put("nullable", isNullable.invoke(navArg));
                        } catch (Exception ignored2) {}
                        try {
                            Method getDefault = navArg.getClass().getMethod("getDefaultValue");
                            Object def = getDefault.invoke(navArg);
                            if (def != null) argInfo.put("defaultValue", def.toString());
                        } catch (Exception ignored2) {}
                    }
                    argsList.add(argInfo);
                }
                map.put("arguments", argsList);
            }
        } catch (Exception ignored) {}

        // Deep links
        try {
            Field deepLinksField = dest.getClass().getDeclaredField("deepLinks");
            deepLinksField.setAccessible(true);
            Object deepLinks = deepLinksField.get(dest);
            if (deepLinks instanceof List && !((List<?>) deepLinks).isEmpty()) {
                List<String> uris = new ArrayList<>();
                for (Object dl : (List<?>) deepLinks) {
                    try {
                        Method getUriPattern = dl.getClass().getMethod("getUriPattern");
                        Object uri = getUriPattern.invoke(dl);
                        if (uri != null) uris.add(uri.toString());
                    } catch (Exception ignored2) {}
                }
                if (!uris.isEmpty()) map.put("deepLinks", uris);
            }
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

    // ========================================================================
    // ViewModels
    // ========================================================================

    /** Hard cap on the rendered length of any single property value. */
    private static final int MAX_VALUE_LEN = 1000;

    /**
     * Walks every live Activity and pulls its {@link androidx.lifecycle.ViewModelStore}.
     * For each held ViewModel we reflect declared fields and unwrap the common
     * holders ({@code LiveData}, {@code StateFlow}, Compose {@code State}) so the
     * caller sees the current value, not the wrapper. Truncates per
     * {@link #MAX_VALUE_LEN} so a list-of-thousand-items VM doesn't blow up
     * the response.
     *
     * <p>v1 scope: Activity-scoped only. Fragment / NavBackStackEntry scopes
     * are tracked under issue #48 follow-up.</p>
     */
    public Map<String, Object> viewModels() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> all = new ArrayList<>();

        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = atClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesMap = activitiesField.get(at);
            if (!(activitiesMap instanceof Map)) {
                result.put("error", "ActivityThread.mActivities was not a Map");
                result.put("viewModels", all);
                return result;
            }

            for (Object record : ((Map<?, ?>) activitiesMap).values()) {
                Activity activity;
                try {
                    Field actField = record.getClass().getDeclaredField("activity");
                    actField.setAccessible(true);
                    activity = (Activity) actField.get(record);
                } catch (Exception ignored) { continue; }
                if (activity == null) continue;

                Map<String, ViewModelEntry> store = readViewModelStore(activity);
                if (store == null || store.isEmpty()) continue;

                for (Map.Entry<String, ViewModelEntry> e : store.entrySet()) {
                    Map<String, Object> vm = new HashMap<>();
                    vm.put("id", e.getKey());
                    vm.put("vmClass", e.getValue().vm.getClass().getName());
                    Map<String, Object> owner = new HashMap<>();
                    owner.put("type", "Activity");
                    owner.put("name", activity.getClass().getName());
                    owner.put("scope", activity.getClass().getName() + "@" + activity.getTaskId());
                    vm.put("owner", owner);
                    vm.put("properties", reflectViewModelProperties(e.getValue().vm));
                    all.add(vm);
                }
            }
        } catch (Exception e) {
            result.put("error", "Failed to enumerate ViewModels: " + e.getMessage());
        }

        result.put("viewModels", all);
        result.put("count", all.size());
        return result;
    }

    /**
     * Returns the {@code SavedStateHandle} contents (a flat key/value map) for a
     * specific ViewModel, addressed by the same {@code id} returned from
     * {@link #viewModels()}. ViewModels without a SavedStateHandle return an
     * empty map.
     */
    public Map<String, Object> viewModelSavedState(String viewModelId) {
        Map<String, Object> result = new HashMap<>();
        if (viewModelId == null || viewModelId.isEmpty()) {
            result.put("error", "viewModelId is required");
            return result;
        }

        Object vm = findViewModelById(viewModelId);
        if (vm == null) {
            result.put("error", "ViewModel not found for id: " + viewModelId);
            return result;
        }

        Map<String, Object> state = new HashMap<>();
        Object handle = findSavedStateHandle(vm);
        if (handle != null) {
            try {
                Method keysMethod = handle.getClass().getMethod("keys");
                @SuppressWarnings("unchecked")
                Set<String> keys = (Set<String>) keysMethod.invoke(handle);
                Method getMethod = handle.getClass().getMethod("get", String.class);
                if (keys != null) {
                    for (String key : keys) {
                        Object value = getMethod.invoke(handle, key);
                        state.put(key, renderValue(value));
                    }
                }
            } catch (Exception e) {
                result.put("error", "Failed to read SavedStateHandle: " + e.getMessage());
            }
        } else {
            result.put("note", "ViewModel has no SavedStateHandle field");
        }
        result.put("state", state);
        result.put("viewModelId", viewModelId);
        return result;
    }

    /** Pair of (key, vm) extracted from a ViewModelStore — keeps the API tidy. */
    private static class ViewModelEntry {
        final Object vm;
        ViewModelEntry(Object vm) { this.vm = vm; }
    }

    /**
     * Reads the underlying map from {@code activity.getViewModelStore()}.
     * AndroidX historically stored this as a Java {@code mMap} field, but the
     * lifecycle library was migrated to Kotlin and the field is now called
     * {@code map}. We try both, plus any field whose runtime type is a
     * {@code Map} as a final fallback for future renames. Null if the VM
     * library isn't present.
     */
    private Map<String, ViewModelEntry> readViewModelStore(Activity activity) {
        try {
            Method getStore = activity.getClass().getMethod("getViewModelStore");
            Object store = getStore.invoke(activity);
            if (store == null) return null;

            Field mapField = findField(store.getClass(), "map");
            if (mapField == null) mapField = findField(store.getClass(), "mMap");
            if (mapField == null) mapField = findFirstMapField(store.getClass());
            if (mapField == null) {
                SidekickLog.d(TAG, "ViewModelStore has no recognizable map field on " + store.getClass());
                return null;
            }
            mapField.setAccessible(true);
            Object raw = mapField.get(store);
            if (!(raw instanceof Map)) return null;
            Map<String, ViewModelEntry> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() != null) {
                    out.put((String) e.getKey(), new ViewModelEntry(e.getValue()));
                }
            }
            return out;
        } catch (NoSuchMethodException ignored) {
            // Pre-AndroidX or non-ComponentActivity — no ViewModelStore.
            return null;
        } catch (Exception e) {
            SidekickLog.d(TAG, "readViewModelStore failed: " + e.getMessage());
            return null;
        }
    }

    /** Walks the class hierarchy returning the first non-static {@code Map}-typed field. */
    private static Field findFirstMapField(Class<?> cls) {
        while (cls != null) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (Map.class.isAssignableFrom(f.getType())) return f;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Walks the activity → vm-store map to find a vm matching {@code id}. */
    private Object findViewModelById(String id) {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = atClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesMap = activitiesField.get(at);
            if (!(activitiesMap instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) activitiesMap).values()) {
                Field actField = record.getClass().getDeclaredField("activity");
                actField.setAccessible(true);
                Activity activity = (Activity) actField.get(record);
                if (activity == null) continue;
                Map<String, ViewModelEntry> store = readViewModelStore(activity);
                if (store != null && store.containsKey(id)) {
                    return store.get(id).vm;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Reflects all declared fields of the VM and produces a
     * {@code [{name, type, value}]} list. Unwraps LiveData / StateFlow /
     * Compose State to show the inner value, since the wrapper itself is
     * never what the user wants to see.
     *
     * <p>Kotlin's "private mutable backing, public read-only view" pattern
     * generates two fields per logical property ({@code _foo} and {@code foo}).
     * Both unwrap to the same value, so we dedupe on the normalized name and
     * prefer the public (non-underscore) field's <i>type</i> for display —
     * users care that they're seeing a {@code StateFlow<Foo>} not a
     * {@code MutableStateFlow<Foo>}.</p>
     */
    private List<Map<String, Object>> reflectViewModelProperties(Object vm) {
        java.util.LinkedHashMap<String, Map<String, Object>> byName = new java.util.LinkedHashMap<>();
        Class<?> cls = vm.getClass();
        // Walk up to the ViewModel base — beyond that is framework noise.
        while (cls != null && !cls.getName().equals("androidx.lifecycle.ViewModel")
                && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                    Object raw = f.get(vm);
                    Object unwrapped = unwrapHolder(raw);
                    String displayName = normalizeFieldName(f.getName());
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", displayName);
                    p.put("type", describeType(raw));
                    p.put("value", renderValue(unwrapped));

                    Map<String, Object> existing = byName.get(displayName);
                    if (existing == null) {
                        byName.put(displayName, p);
                    } else if (f.getName().equals(displayName)) {
                        // Public-named field beats the underscore-prefixed
                        // backing field for type display purposes.
                        byName.put(displayName, p);
                    }
                } catch (Throwable ignored) {
                    // Field may be inaccessible on Android's hidden API list,
                    // or its toString may throw. Skip silently — partial data
                    // is fine.
                }
            }
            cls = cls.getSuperclass();
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * If {@code raw} is a known wrapper (LiveData, StateFlow, Compose State,
     * Kotlin Lazy), returns its inner value; otherwise returns {@code raw}
     * unchanged. Soft-fails on missing methods so the caller still sees
     * something useful.
     */
    private Object unwrapHolder(Object raw) {
        if (raw == null) return null;
        // androidx.lifecycle.LiveData has getValue()
        if (isInstanceOf(raw, "androidx.lifecycle.LiveData")) {
            return invokeNoArg(raw, "getValue");
        }
        // kotlinx.coroutines.flow.StateFlow has getValue() too
        if (isInstanceOf(raw, "kotlinx.coroutines.flow.StateFlow")) {
            return invokeNoArg(raw, "getValue");
        }
        // androidx.compose.runtime.State.getValue()
        if (isInstanceOf(raw, "androidx.compose.runtime.State")) {
            return invokeNoArg(raw, "getValue");
        }
        // kotlin.Lazy<T> — common for derived caches
        if (isInstanceOf(raw, "kotlin.Lazy")) {
            return invokeNoArg(raw, "getValue");
        }
        return raw;
    }

    private boolean isInstanceOf(Object o, String className) {
        try {
            Class<?> c = Class.forName(className, false, o.getClass().getClassLoader());
            return c.isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    private Object invokeNoArg(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return target;
        }
    }

    /**
     * Converts {@code _user} → {@code user} (the Kotlin convention for "private
     * mutable backing field, public read-only view"). Leaves field names that
     * already look public alone.
     */
    private String normalizeFieldName(String fieldName) {
        if (fieldName.startsWith("_") && fieldName.length() > 1) {
            return fieldName.substring(1);
        }
        if (fieldName.endsWith("$delegate") && fieldName.length() > "$delegate".length()) {
            // Kotlin's by-delegation desugaring — show the property name only.
            return fieldName.substring(0, fieldName.length() - "$delegate".length());
        }
        return fieldName;
    }

    private String describeType(Object raw) {
        if (raw == null) return "null";
        return raw.getClass().getName();
    }

    private String renderValue(Object value) {
        String s;
        try {
            s = value == null ? "null" : String.valueOf(value);
        } catch (Throwable t) {
            return "<toString threw: " + t.getClass().getSimpleName() + ">";
        }
        if (s.length() > MAX_VALUE_LEN) {
            return s.substring(0, MAX_VALUE_LEN) + "… (truncated, " + s.length() + " chars)";
        }
        return s;
    }

    /** Looks up a SavedStateHandle stored as a field on the VM. */
    private Object findSavedStateHandle(Object vm) {
        Class<?> cls = vm.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(vm);
                    if (v != null && isInstanceOf(v, "androidx.lifecycle.SavedStateHandle")) {
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Walks up the class hierarchy looking for a declared field by name. */
    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
