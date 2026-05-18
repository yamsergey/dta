package io.yamsergey.dta.sidekick.data;

import android.app.Activity;
import android.os.Debug;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.view.WindowRootDiscovery;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * Pushes a destination onto the host app's NavController. Supports the
     * Navigation 2 / Compose Navigation string-route API: caller passes a
     * {@code destination} that matches a {@code <destination>} route in the
     * graph (e.g. {@code "topic/{topicId}"} or just {@code "login"}) plus a
     * {@code params} map; placeholders in the route template are substituted
     * verbatim, then {@code NavController.navigate(String)} is invoked on
     * the main thread.
     *
     * <p>Navigation 3 (NavBackStack/NavKey) is not supported — there is no
     * canonical owner to reach via reflection. See sibling issue for the
     * research thread.</p>
     *
     * @param destination route template (with optional {@code {placeholder}} segments)
     *                    or a literal route already filled by the caller.
     * @param params      map of placeholder name → value (any type with a sane
     *                    {@code toString()}). Missing required placeholders fail
     *                    fast; extras are appended as query parameters when the
     *                    route doesn't already declare them.
     */
    public Map<String, Object> navigate(String destination, Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        if (destination == null || destination.isEmpty()) {
            result.put("error", "Missing required 'destination' parameter");
            return result;
        }
        Activity activity = WindowRootDiscovery.getCurrentActivity();
        if (activity == null) {
            result.put("error", "No visible activity — host app must be foreground");
            return result;
        }

        Object navController;
        try {
            navController = runOnMainThread(() -> findNavController(activity));
        } catch (Exception e) {
            result.put("error", "NavController lookup failed: " + e.getMessage());
            return result;
        }
        if (navController == null) {
            result.put("error", "No NavController found. Navigation 2 / Compose Navigation NavController "
                + "required. Navigation 3 (NavBackStack<NavKey>) is not yet supported — use open_deeplink "
                + "if the destination declares an intent-filter.");
            return result;
        }

        String filledRoute;
        try {
            filledRoute = fillRouteTemplate(destination, params != null ? params : Collections.emptyMap());
        } catch (IllegalArgumentException e) {
            result.put("error", e.getMessage());
            return result;
        }

        // Run on main thread because NavController mutates Compose state +
        // requires the same thread the host's NavHost is running on.
        Throwable[] err = new Throwable[1];
        runOnMainThread(() -> {
            try {
                Method navigate = navController.getClass().getMethod("navigate", String.class);
                navigate.invoke(navController, filledRoute);
            } catch (Throwable t) {
                // Some Compose Navigation versions also accept (String, NavOptions);
                // the String-only overload is the most portable. Surface the
                // underlying cause to the caller — typical: "Navigation destination
                // X cannot be found from the current destination".
                err[0] = (t.getCause() != null) ? t.getCause() : t;
            }
            return null;
        });
        if (err[0] != null) {
            result.put("error", err[0].getClass().getSimpleName() + ": " + err[0].getMessage());
            result.put("attemptedRoute", filledRoute);
            return result;
        }
        result.put("status", "ok");
        result.put("route", filledRoute);
        return result;
    }

    /**
     * Substitutes {@code {name}} placeholders in a route template with values
     * from {@code params}. Any unused params are appended as query parameters,
     * mirroring how Compose Navigation lets callers pass optional args.
     * Throws if a placeholder has no matching value.
     */
    private String fillRouteTemplate(String template, Map<String, Object> params) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        java.util.Set<String> consumed = new java.util.LinkedHashSet<>();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                // Stray '{' — copy literally.
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            String name = template.substring(open + 1, close);
            Object value = params.get(name);
            if (value == null) {
                throw new IllegalArgumentException(
                    "Missing required param '" + name + "' for route template '" + template + "'");
            }
            // URL-encode minimally so spaces / special chars round-trip
            // through the NavController route parser. NavController itself
            // expects the standard encoding for path segments.
            out.append(java.net.URLEncoder.encode(value.toString(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20"));
            consumed.add(name);
            i = close + 1;
        }
        // Append unused params as query string — only if the template doesn't
        // already carry a '?' (then it's up to the caller to format).
        boolean hasQuery = out.indexOf("?") >= 0;
        boolean first = !hasQuery;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (consumed.contains(e.getKey()) || e.getValue() == null) continue;
            out.append(first ? '?' : '&');
            first = false;
            out.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8));
            out.append('=');
            out.append(java.net.URLEncoder.encode(e.getValue().toString(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /**
     * Fires {@code Intent.ACTION_VIEW} with the given URI. Works for any
     * destination the app exposes via an {@code <intent-filter>} — independent
     * of which navigation library the app uses. The intent is launched from
     * the foreground activity so it inherits the host's task affinity (no
     * external "open in browser" detour).
     */
    public Map<String, Object> openDeepLink(String uri) {
        Map<String, Object> result = new HashMap<>();
        if (uri == null || uri.isEmpty()) {
            result.put("error", "Missing required 'uri' parameter");
            return result;
        }
        Activity activity = WindowRootDiscovery.getCurrentActivity();
        if (activity == null) {
            result.put("error", "No visible activity — host app must be foreground to launch a deep link in-task");
            return result;
        }
        android.net.Uri parsed;
        try {
            parsed = android.net.Uri.parse(uri);
        } catch (Exception e) {
            result.put("error", "Invalid URI: " + e.getMessage());
            return result;
        }
        Throwable[] err = new Throwable[1];
        runOnMainThread(() -> {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, parsed);
                // Launch from the activity to keep the same task; if no handler
                // claims it, Android throws ActivityNotFoundException — surface
                // it to the caller so they know the URI didn't match anything.
                activity.startActivity(intent);
            } catch (Throwable t) {
                err[0] = t;
            }
            return null;
        });
        if (err[0] != null) {
            result.put("error", err[0].getClass().getSimpleName() + ": " + err[0].getMessage());
            result.put("attemptedUri", uri);
            return result;
        }
        result.put("status", "ok");
        result.put("uri", uri);
        return result;
    }

    /**
     * Polls the current view tree at fixed 50 ms intervals until a node
     * matching the predicate appears, or until {@code maxMs} elapses.
     * Designed for capturing transient UI (snackbars, toasts, brief
     * loaders) that disappears faster than the daemon round-trip.
     *
     * <p>Polling runs in-process inside the host app — no adb / HTTP
     * hops per check — so the actual sampling rate is much closer to
     * 50 ms than what an external poller could achieve.</p>
     *
     * <p>Predicate fields (all optional, AND-combined when multiple
     * are non-null):</p>
     * <ul>
     *   <li>{@code text}: substring match, case-insensitive, against
     *       the node's {@code text} field.</li>
     *   <li>{@code testTag}: exact match against the node's
     *       {@code testTag} field (Compose {@code Modifier.testTag}).</li>
     *   <li>{@code className}: exact match against the node's
     *       {@code className} (View nodes) or {@code composable}
     *       (Compose nodes). Use the simple name — e.g.
     *       {@code "Snackbar"} matches both
     *       {@code "androidx.compose.material3.SnackbarHost"} (suffix)
     *       and the bare composable name.</li>
     * </ul>
     *
     * <p>On match, returns {@code {matched:true, elapsedMs, matchedNode,
     * layoutTree, screenshot (base64 PNG)}}. On timeout, returns
     * {@code {matched:false, elapsedMs}}.</p>
     */
    public Map<String, Object> waitFor(String text, String testTag, String className, int maxMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if ((text == null || text.isEmpty())
                && (testTag == null || testTag.isEmpty())
                && (className == null || className.isEmpty())) {
            result.put("error", "At least one predicate (text / testTag / className) must be non-empty");
            return result;
        }
        if (maxMs <= 0) maxMs = 3000;

        long start = System.currentTimeMillis();
        long deadline = start + maxMs;
        String textLower = text != null ? text.toLowerCase() : null;

        while (true) {
            // Capture on the main thread (view-tree traversal isn't
            // thread-safe). Bail with a short sleep + retry if we got
            // null — typically a no-activity transient.
            Map<String, Object> tree = runOnMainThread(
                () -> io.yamsergey.dta.sidekick.layout.UnifiedTreeBuilder.capture());
            if (tree != null) {
                Map<String, Object> matched = findFirstMatch(tree, textLower, testTag, className);
                if (matched != null) {
                    result.put("matched", true);
                    result.put("elapsedMs", System.currentTimeMillis() - start);
                    result.put("matchedNode", matched);
                    result.put("layoutTree", tree);
                    byte[] png = captureScreenshotBytes();
                    if (png != null) {
                        result.put("screenshot",
                            java.util.Base64.getEncoder().encodeToString(png));
                        result.put("screenshotEncoding", "base64");
                        result.put("screenshotFormat", "png");
                    }
                    return result;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                result.put("matched", false);
                result.put("elapsedMs", System.currentTimeMillis() - start);
                return result;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                result.put("matched", false);
                result.put("elapsedMs", System.currentTimeMillis() - start);
                result.put("error", "Interrupted");
                return result;
            }
        }
    }

    /**
     * Walks {@code tree} depth-first, returning the first node whose
     * fields satisfy every non-null predicate component. {@code text}
     * arrives pre-lowercased; callers pass the original-case form for
     * exact-match predicates ({@code testTag}, {@code className}).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findFirstMatch(Map<String, Object> tree,
            String textLower, String testTag, String className) {
        // Walk both `windows` (top-level result) and `children` (within nodes).
        Object windows = tree.get("windows");
        if (windows instanceof List) {
            for (Object w : (List<?>) windows) {
                if (w instanceof Map) {
                    Object subtree = ((Map<String, Object>) w).get("tree");
                    if (subtree instanceof Map) {
                        Map<String, Object> hit = walkForMatch(
                            (Map<String, Object>) subtree, textLower, testTag, className);
                        if (hit != null) return hit;
                    }
                }
            }
        }
        // Some callers may pass a single-node tree.
        return walkForMatch(tree, textLower, testTag, className);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> walkForMatch(Map<String, Object> node,
            String textLower, String testTag, String className) {
        if (node == null) return null;
        if (nodeMatches(node, textLower, testTag, className)) return node;
        Object children = node.get("children");
        if (children instanceof List) {
            for (Object child : (List<?>) children) {
                if (child instanceof Map) {
                    Map<String, Object> hit = walkForMatch(
                        (Map<String, Object>) child, textLower, testTag, className);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
    }

    private boolean nodeMatches(Map<String, Object> node,
            String textLower, String testTag, String className) {
        if (textLower != null) {
            Object t = node.get("text");
            if (!(t instanceof String) || !((String) t).toLowerCase().contains(textLower)) {
                return false;
            }
        }
        if (testTag != null && !testTag.isEmpty()) {
            Object tag = node.get("testTag");
            if (!(tag instanceof String) || !testTag.equals(tag)) return false;
        }
        if (className != null && !className.isEmpty()) {
            // Match either View `className` (FQ — accept a suffix) or
            // Compose `composable` (typically the simple name already).
            Object cn = node.get("className");
            Object cp = node.get("composable");
            boolean classHit = cn instanceof String
                && (className.equals(cn) || ((String) cn).endsWith("." + className)
                    || ((String) cn).endsWith("$" + className));
            boolean composeHit = cp instanceof String && className.equals(cp);
            if (!classHit && !composeHit) return false;
        }
        return true;
    }

    /**
     * Lists the Hilt-generated bindings reachable from the foreground
     * activity's component graph: Activity + ActivityRetained +
     * Singleton scopes. Surfaces each binding's field name (the Hilt
     * generator's name, which mirrors the source interface in most
     * cases), the field's declared type, and the runtime implementation
     * class.
     *
     * <p>Answers the methodology question "which concrete impl is wired
     * for interface X in this build?" without restarting the app under
     * test instrumentation. The canonical case is
     * StubAnalyticsHelper-vs-Firebase: the demo build wires
     * StubAnalyticsHelper, the prod build wires FirebaseAnalyticsHelper;
     * researchers need to know which without rebuilding.</p>
     *
     * <p>The walk uses ActivityRetainedComponentViewModel.component as
     * the anchor (we already enumerate this VM in {@link #viewModels}).
     * From there we traverse to the SingletonCImpl parent via the
     * Hilt-generated reference field {@code singletonCImpl} (alternate
     * names tried as fallbacks). Fields whose names start with {@code $}
     * (Jacoco) or that hold framework plumbing (Provider wrappers with
     * no useful information at this layer) are skipped.</p>
     *
     * @param interfaceFilter substring match against field's declared
     *                        type FQ name. When non-empty, only bindings
     *                        whose declared type contains this substring
     *                        are surfaced. Used to answer the targeted
     *                        question "what's wired for AnalyticsHelper?"
     *                        without paging through the whole graph.
     */
    public Map<String, Object> hiltBindings(String interfaceFilter) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> bindings = new ArrayList<>();
        try {
            List<Object> components = new ArrayList<>();

            // SingletonCImpl is reachable through the Application's
            // generated componentManager — doesn't require any activity
            // to be resumed. This is the anchor for graph walks.
            Object app = getApplicationInstance();
            Object singleton = app != null ? findActivityComponent(app) : null;
            if (singleton != null) {
                components.add(singleton);
            }

            // ActivityRetained scope (when an activity exists at all,
            // even paused) — found via the activity's ViewModelStore.
            // We try the resumed activity first, then fall back to any
            // activity in the process. If none, we silently skip — the
            // singleton scope alone is still useful.
            Activity activity = WindowRootDiscovery.getCurrentActivity();
            if (activity == null) activity = findAnyActivity();
            if (activity != null) {
                Object retainedComponent = findRetainedComponent(activity);
                if (retainedComponent != null) components.add(retainedComponent);
                // Activity-scoped — the activity itself holds an
                // ActivityCImpl via its GeneratedComponentManager.
                Object activityComponent = findActivityComponent(activity);
                if (activityComponent != null) components.add(activityComponent);
            }

            if (components.isEmpty()) {
                result.put("error", "No Hilt components discoverable — host app may not use Hilt, "
                    + "or the Application class hasn't initialized yet");
                return result;
            }

            String filterLower = interfaceFilter != null && !interfaceFilter.isEmpty()
                ? interfaceFilter.toLowerCase() : null;
            for (Object component : components) {
                bindings.addAll(componentBindings(component, filterLower));
            }
            result.put("bindings", bindings);
            result.put("count", bindings.size());
        } catch (Exception e) {
            SidekickLog.d(TAG, "hiltBindings failed", e);
            result.put("error", "Reflection failed: " + e.getMessage());
        }
        return result;
    }

    /** Returns the host app's {@link android.app.Application} singleton, or null. */
    private Object getApplicationInstance() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            return at.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns the first activity tracked in {@code ActivityThread.mActivities}
     * regardless of paused state — used as a fallback when no activity is
     * resumed but we still want the ActivityRetained/Activity scopes.
     */
    private Activity findAnyActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = at.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object map = activitiesField.get(thread);
            if (!(map instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) map).values()) {
                Field actField = record.getClass().getDeclaredField("activity");
                actField.setAccessible(true);
                Activity a = (Activity) actField.get(record);
                if (a != null) return a;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Extracts the {@code ActivityRetainedCImpl} for the given activity
     * via the {@code ActivityRetainedComponentManager$ActivityRetainedComponentViewModel.component}
     * field that Hilt keeps in the activity's retained ViewModelStore.
     */
    private Object findRetainedComponent(Activity activity) {
        try {
            Map<String, ViewModelEntry> store = readViewModelStore(activity);
            if (store == null) return null;
            for (ViewModelEntry e : store.values()) {
                String cls = e.vm.getClass().getName();
                if (cls.contains("ActivityRetainedComponentViewModel")) {
                    Field componentField = findField(e.vm.getClass(), "component");
                    if (componentField == null) continue;
                    componentField.setAccessible(true);
                    return componentField.get(e.vm);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object findParentComponent(Object component, String... candidateNames) {
        for (String name : candidateNames) {
            Field f = findField(component.getClass(), name);
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Object parent = f.get(component);
                if (parent != null) return parent;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Finds the Hilt-generated component for any
     * {@code GeneratedComponentManagerHolder} (Application →
     * SingletonCImpl, Activity → ActivityCImpl, Fragment →
     * FragmentCImpl, etc.). Pattern: holder has a no-arg
     * {@code componentManager()} that returns a manager whose
     * {@code generatedComponent()} returns the component.
     */
    private Object findActivityComponent(Object holder) {
        try {
            Method cm = findMethod(holder.getClass(), "componentManager");
            if (cm == null) return null;
            cm.setAccessible(true);
            Object manager = cm.invoke(holder);
            if (manager == null) return null;
            Method gen = findMethod(manager.getClass(), "generatedComponent");
            if (gen == null) return null;
            gen.setAccessible(true);
            return gen.invoke(manager);
        } catch (Throwable t) {
            return null;
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private List<Map<String, Object>> componentBindings(Object component, String filterLower) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (component == null) return out;
        String componentClass = component.getClass().getName();
        String scope = simpleScopeLabel(componentClass);
        Class<?> cls = component.getClass();
        while (cls != null
                // Walk only the Hilt-generated impl + its synthetic super
                // (Object). Walking into framework parents adds noise.
                && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                String name = f.getName();
                // Skip jacoco / synthetic / our own walker tracks.
                if (name.startsWith("$") || f.isSynthetic()) continue;
                // Skip back-references we already followed.
                if (name.equals("singletonCImpl") || name.equals("singletonC")
                        || name.equals("activityRetainedCImpl") || name.equals("appComponent")) continue;

                // Hilt generates the field as Provider<X> / Lazy<X>; the
                // interesting binding is X, not the wrapper. Read the
                // parameterized type from the generic signature so the
                // `interface=X` filter matches what users actually mean.
                String declaredType = f.getType().getName();
                String boundType = declaredType;
                if (isWrapperType(declaredType)) {
                    String inner = parameterizedTypeArg(f.getGenericType());
                    if (inner != null) boundType = inner;
                }
                if (filterLower != null
                        && !boundType.toLowerCase().contains(filterLower)
                        && !declaredType.toLowerCase().contains(filterLower)) continue;

                Map<String, Object> binding = new LinkedHashMap<>();
                binding.put("scope", scope);
                binding.put("name", name);
                binding.put("boundType", boundType);
                if (!boundType.equals(declaredType)) binding.put("declaredType", declaredType);
                try {
                    f.setAccessible(true);
                    Object value = f.get(component);
                    if (value != null) {
                        binding.put("runtimeImpl", value.getClass().getName());
                        // If the field is a Provider/Lazy and the caller
                        // asked about THIS binding specifically (filter
                        // matched the parameterized type), materialize the
                        // wrapped instance so they can see the concrete
                        // impl class — that's the canonical question the
                        // research methodology wants answered. Skipped
                        // for unfiltered walks to avoid eagerly
                        // instantiating every binding.
                        if (filterLower != null && isWrapperType(declaredType)) {
                            Object unwrapped = unwrapProvider(value);
                            if (unwrapped != null && unwrapped != value) {
                                binding.put("providedImpl", unwrapped.getClass().getName());
                            }
                        }
                    } else {
                        binding.put("runtimeImpl", null);
                    }
                } catch (Throwable t) {
                    binding.put("runtimeImpl", "<inaccessible: " + t.getClass().getSimpleName() + ">");
                }
                out.add(binding);
            }
            cls = cls.getSuperclass();
        }
        return out;
    }

    private static boolean isWrapperType(String fqName) {
        return "javax.inject.Provider".equals(fqName)
            || "dagger.Lazy".equals(fqName)
            || "dagger.internal.DoubleCheck".equals(fqName)
            || "dagger.internal.SingleCheck".equals(fqName)
            || "dagger.internal.Provider".equals(fqName);
    }

    /**
     * For {@code Provider<X> field;} returns the FQ name of {@code X}.
     * Returns null when the field is raw or when the type argument can't
     * be statically resolved.
     */
    private static String parameterizedTypeArg(java.lang.reflect.Type type) {
        if (!(type instanceof java.lang.reflect.ParameterizedType)) return null;
        java.lang.reflect.Type[] args = ((java.lang.reflect.ParameterizedType) type).getActualTypeArguments();
        if (args.length == 0) return null;
        java.lang.reflect.Type t0 = args[0];
        if (t0 instanceof Class) return ((Class<?>) t0).getName();
        // Could be a TypeVariable / WildcardType / ParameterizedType — fall back to toString.
        return t0.toString();
    }

    /**
     * Calls {@code .get()} on a {@code javax.inject.Provider} (or Dagger
     * {@code Lazy}) to materialize the wrapped instance. Returns the
     * argument unchanged if it isn't a known wrapper, or null on failure.
     * <strong>Side-effecting</strong> — only call when the caller has
     * narrowed to a specific binding via the {@code interface=} filter.
     */
    private static Object unwrapProvider(Object value) {
        if (value == null) return null;
        for (String cls : new String[]{"javax.inject.Provider", "dagger.Lazy"}) {
            try {
                Class<?> ifaceClass = Class.forName(cls);
                if (ifaceClass.isInstance(value)) {
                    Method get = ifaceClass.getMethod("get");
                    return get.invoke(value);
                }
            } catch (Throwable ignored) {}
        }
        // DoubleCheck / SingleCheck implement Provider so the loop above handles them.
        return value;
    }

    private String simpleScopeLabel(String fqClassName) {
        // Hilt impl classes follow Dagger…_HiltComponents_<Scope>$<ScopeC>Impl.
        // Try to extract a short label like "Singleton" / "ActivityRetained" / "Activity".
        if (fqClassName.contains("ActivityRetainedCImpl")) return "ActivityRetained";
        if (fqClassName.contains("SingletonCImpl")) return "Singleton";
        if (fqClassName.contains("ActivityCImpl")) return "Activity";
        if (fqClassName.contains("ViewModelCImpl")) return "ViewModel";
        if (fqClassName.contains("FragmentCImpl")) return "Fragment";
        if (fqClassName.contains("ServiceCImpl")) return "Service";
        // Fallback: simple name.
        int dot = fqClassName.lastIndexOf('.');
        return dot >= 0 ? fqClassName.substring(dot + 1) : fqClassName;
    }

    /**
     * Captures a PNG of the foreground activity's window. Mirrors what
     * the {@code /compose/screenshot} handler does — main-thread window
     * lookup, off-main-thread PixelCopy. Returns null if no activity or
     * capture failed; callers tolerate a missing screenshot rather than
     * failing the whole wait_for response.
     */
    private byte[] captureScreenshotBytes() {
        try {
            android.view.Window window = runOnMainThread(() -> {
                Activity activity = WindowRootDiscovery.getCurrentActivity();
                return activity != null ? activity.getWindow() : null;
            });
            if (window == null) return null;
            return io.yamsergey.dta.sidekick.compose.ComposeHitTester.captureScreenshot(window);
        } catch (Throwable t) {
            SidekickLog.d(TAG, "screenshot capture failed during wait_for: " + t.getMessage());
            return null;
        }
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

                String activityScope = activity.getClass().getName() + "@" + activity.getTaskId();

                for (Map.Entry<String, ViewModelEntry> e : store.entrySet()) {
                    Object vmInstance = e.getValue().vm;
                    Map<String, Object> vm = new HashMap<>();
                    vm.put("id", e.getKey());
                    vm.put("vmClass", vmInstance.getClass().getName());
                    Map<String, Object> owner = new HashMap<>();
                    owner.put("type", "Activity");
                    owner.put("name", activity.getClass().getName());
                    owner.put("scope", activityScope);
                    vm.put("owner", owner);
                    vm.put("properties", reflectViewModelProperties(vmInstance));
                    all.add(vm);

                    // Navigation 3: this Activity-scoped VM is the EntryViewModel
                    // holding per-NavKey ViewModelStores. Walk them so callers see
                    // ForYouViewModel, TopicViewModel, etc. that live in a NavEntry.
                    for (Map.Entry<Object, Map<String, ViewModelEntry>> navEntry :
                            readNavEntryStores(vmInstance).entrySet()) {
                        Object navKey = navEntry.getKey();
                        String navKeyStr = String.valueOf(navKey);
                        String navKeyClass = navKey != null ? navKey.getClass().getName() : "null";
                        for (Map.Entry<String, ViewModelEntry> nve : navEntry.getValue().entrySet()) {
                            Object navVm = nve.getValue().vm;
                            Map<String, Object> nvVm = new HashMap<>();
                            nvVm.put("id", navEntryVmId(navKey, nve.getKey()));
                            nvVm.put("vmClass", navVm.getClass().getName());
                            Map<String, Object> nvOwner = new HashMap<>();
                            nvOwner.put("type", "NavEntry");
                            nvOwner.put("key", navKeyStr);
                            nvOwner.put("navKeyClass", navKeyClass);
                            nvOwner.put("activity", activityScope);
                            nvVm.put("owner", nvOwner);
                            nvVm.put("properties", reflectViewModelProperties(navVm));
                            all.add(nvVm);
                        }
                    }
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
     */
    private Map<String, ViewModelEntry> readViewModelStore(Activity activity) {
        try {
            Method getStore = activity.getClass().getMethod("getViewModelStore");
            return readStoreMap(getStore.invoke(activity));
        } catch (NoSuchMethodException ignored) {
            // Pre-AndroidX or non-ComponentActivity — no ViewModelStore.
            return null;
        } catch (Exception e) {
            SidekickLog.d(TAG, "readViewModelStore failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads the underlying {@code Map<String, ViewModel>} out of any
     * {@code ViewModelStore}-like object (Activity-, Fragment-, or
     * Navigation 3 NavEntry-scoped).
     *
     * <p>AndroidX historically stored this as a Java {@code mMap} field, but the
     * lifecycle library was migrated to Kotlin and the field is now called
     * {@code map}. We try both, plus any field whose runtime type is a
     * {@code Map} as a final fallback for future renames.</p>
     */
    private Map<String, ViewModelEntry> readStoreMap(Object store) {
        if (store == null) return null;
        Field mapField = findField(store.getClass(), "map");
        if (mapField == null) mapField = findField(store.getClass(), "mMap");
        if (mapField == null) mapField = findFirstMapField(store.getClass());
        if (mapField == null) {
            SidekickLog.d(TAG, "ViewModelStore has no recognizable map field on " + store.getClass());
            return null;
        }
        try {
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
        } catch (Exception e) {
            SidekickLog.d(TAG, "readStoreMap failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Class name of Navigation 3's per-Activity holder for NavEntry-scoped
     * stores. Lives in {@code androidx.lifecycle:lifecycle-viewmodel-navigation3}
     * (alpha API in 2.10.x); has a private {@code Map<Object, ViewModelStore>
     * owners} field keyed by the host app's NavKey type.
     */
    private static final String NAV3_ENTRY_VIEWMODEL_CLASS =
            "androidx.lifecycle.viewmodel.navigation3.EntryViewModel";

    /**
     * If {@code vm} is the Navigation 3 EntryViewModel, returns its
     * {@code owners} map (NavKey → ViewModelStore-contents-as-map). Empty
     * map for non-matches and for entries the reflection couldn't read.
     */
    private Map<Object, Map<String, ViewModelEntry>> readNavEntryStores(Object vm) {
        Map<Object, Map<String, ViewModelEntry>> out = new java.util.LinkedHashMap<>();
        if (vm == null || !NAV3_ENTRY_VIEWMODEL_CLASS.equals(vm.getClass().getName())) {
            return out;
        }
        try {
            Field owners = findField(vm.getClass(), "owners");
            if (owners == null) return out;
            owners.setAccessible(true);
            Object raw = owners.get(vm);
            if (!(raw instanceof Map)) return out;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                Map<String, ViewModelEntry> storeMap = readStoreMap(e.getValue());
                if (storeMap != null && !storeMap.isEmpty()) {
                    out.put(e.getKey(), storeMap);
                }
            }
        } catch (Exception e) {
            SidekickLog.d(TAG, "readNavEntryStores failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * ID prefix marking NavEntry-scoped VM identifiers. Format:
     * {@code navEntry::<navKeyStr>::<vmKey>}. Double-colon separator keeps
     * the format unambiguous when NavKey toString contains a single colon.
     */
    private static final String NAV_ENTRY_ID_PREFIX = "navEntry::";

    private static String navEntryVmId(Object navKey, String vmKey) {
        return NAV_ENTRY_ID_PREFIX + String.valueOf(navKey) + "::" + vmKey;
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
        // NavEntry-scoped: id = "navEntry::<navKeyStr>::<vmKey>" — match by
        // the string form of the NavKey (the same form viewModels() emits)
        // so callers can paste the listed id back into the lookup.
        boolean isNavEntry = id != null && id.startsWith(NAV_ENTRY_ID_PREFIX);
        String navKeyStr = null;
        String navVmKey = null;
        if (isNavEntry) {
            String tail = id.substring(NAV_ENTRY_ID_PREFIX.length());
            int sep = tail.lastIndexOf("::");
            if (sep < 0) return null;
            navKeyStr = tail.substring(0, sep);
            navVmKey = tail.substring(sep + 2);
        }
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
                if (store == null) continue;

                if (!isNavEntry) {
                    if (store.containsKey(id)) return store.get(id).vm;
                    continue;
                }

                // NavEntry path: locate the EntryViewModel in this store, then
                // match navKeyStr against its owners map.
                for (ViewModelEntry vmEntry : store.values()) {
                    Object vmInstance = vmEntry.vm;
                    if (!NAV3_ENTRY_VIEWMODEL_CLASS.equals(vmInstance.getClass().getName())) continue;
                    Map<Object, Map<String, ViewModelEntry>> navStores = readNavEntryStores(vmInstance);
                    for (Map.Entry<Object, Map<String, ViewModelEntry>> e : navStores.entrySet()) {
                        if (!navKeyStr.equals(String.valueOf(e.getKey()))) continue;
                        ViewModelEntry hit = e.getValue().get(navVmKey);
                        if (hit != null) return hit.vm;
                    }
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
