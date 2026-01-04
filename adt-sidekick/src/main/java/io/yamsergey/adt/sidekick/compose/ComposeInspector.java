package io.yamsergey.adt.sidekick.compose;

import android.app.Activity;
import android.app.Application;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inspector for Jetpack Compose UI hierarchies.
 *
 * <p>Uses reflection to access Compose internal APIs and walk the LayoutNode tree.
 * Must be called from the main thread.</p>
 */
public class ComposeInspector {

    private static final String TAG = "ComposeInspector";

    // Cached class references
    private static Class<?> androidComposeViewClass;
    private static Class<?> layoutNodeClass;
    private static boolean initialized = false;

    /**
     * Captures the full Compose UI hierarchy.
     *
     * @return Map representing the hierarchy, or null if no Compose views found
     */
    public static Map<String, Object> captureHierarchy() {
        ensureInitialized();

        List<Object> composeViews = findComposeViews();
        if (composeViews.isEmpty()) {
            Log.w(TAG, "No Compose views found");
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "compose_hierarchy");
        result.put("timestamp", System.currentTimeMillis());

        List<Map<String, Object>> windows = new ArrayList<>();
        for (Object composeView : composeViews) {
            Map<String, Object> window = captureComposeView(composeView);
            if (window != null) {
                windows.add(window);
            }
        }

        result.put("windows", windows);
        result.put("windowCount", windows.size());

        return result;
    }

    /**
     * Captures only the semantics tree (accessibility-focused).
     *
     * @return Map representing the semantics, or null if no Compose views found
     */
    public static Map<String, Object> captureSemantics() {
        ensureInitialized();

        List<Object> composeViews = findComposeViews();
        if (composeViews.isEmpty()) {
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "compose_semantics");
        result.put("timestamp", System.currentTimeMillis());

        List<Map<String, Object>> trees = new ArrayList<>();
        for (Object composeView : composeViews) {
            Map<String, Object> tree = captureSemanticsTree(composeView);
            if (tree != null) {
                trees.add(tree);
            }
        }

        result.put("trees", trees);
        return result;
    }

    /**
     * Initializes reflection caches.
     */
    private static void ensureInitialized() {
        if (initialized) return;

        try {
            androidComposeViewClass = Class.forName("androidx.compose.ui.platform.AndroidComposeView");
            layoutNodeClass = Class.forName("androidx.compose.ui.node.LayoutNode");
            initialized = true;
            Log.d(TAG, "Compose classes found");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Compose classes not found - app may not use Compose");
        }
    }

    /**
     * Finds all AndroidComposeView instances in the current activity.
     */
    private static List<Object> findComposeViews() {
        List<Object> result = new ArrayList<>();

        if (androidComposeViewClass == null) {
            return result;
        }

        try {
            Activity activity = getCurrentActivity();
            if (activity == null) {
                Log.w(TAG, "No current activity found");
                return result;
            }

            View decorView = activity.getWindow().getDecorView();
            findComposeViewsRecursive(decorView, result);

        } catch (Exception e) {
            Log.e(TAG, "Error finding Compose views", e);
        }

        return result;
    }

    /**
     * Recursively finds ComposeViews in a view hierarchy.
     */
    private static void findComposeViewsRecursive(View view, List<Object> result) {
        if (androidComposeViewClass.isInstance(view)) {
            result.add(view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findComposeViewsRecursive(group.getChildAt(i), result);
            }
        }
    }

    /**
     * Gets the current foreground activity.
     */
    private static Activity getCurrentActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
            Object activityThread = currentMethod.invoke(null);

            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesMap = activitiesField.get(activityThread);

            if (activitiesMap instanceof Map) {
                for (Object activityRecord : ((Map<?, ?>) activitiesMap).values()) {
                    Field activityField = activityRecord.getClass().getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Activity activity = (Activity) activityField.get(activityRecord);

                    if (activity != null) {
                        Field pausedField = activityRecord.getClass().getDeclaredField("paused");
                        pausedField.setAccessible(true);
                        boolean paused = pausedField.getBoolean(activityRecord);

                        if (!paused) {
                            return activity;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting current activity", e);
        }
        return null;
    }

    /**
     * Captures hierarchy from a single ComposeView.
     */
    private static Map<String, Object> captureComposeView(Object composeView) {
        try {
            Map<String, Object> result = new HashMap<>();

            // Get view info
            View view = (View) composeView;
            result.put("viewId", view.getId());
            result.put("width", view.getWidth());
            result.put("height", view.getHeight());

            int[] location = new int[2];
            view.getLocationOnScreen(location);
            result.put("x", location[0]);
            result.put("y", location[1]);

            // Get root LayoutNode
            Object rootLayoutNode = getRootLayoutNode(composeView);
            if (rootLayoutNode != null) {
                result.put("root", captureLayoutNode(rootLayoutNode, 0));
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error capturing ComposeView", e);
            return null;
        }
    }

    /**
     * Gets the root LayoutNode from an AndroidComposeView.
     */
    private static Object getRootLayoutNode(Object composeView) {
        try {
            // Try getRoot() method
            Method getRoot = androidComposeViewClass.getDeclaredMethod("getRoot");
            getRoot.setAccessible(true);
            return getRoot.invoke(composeView);
        } catch (Exception e) {
            // Try root field
            try {
                Field rootField = androidComposeViewClass.getDeclaredField("root");
                rootField.setAccessible(true);
                return rootField.get(composeView);
            } catch (Exception e2) {
                Log.e(TAG, "Cannot get root LayoutNode", e2);
            }
        }
        return null;
    }

    /**
     * Recursively captures a LayoutNode and its children.
     */
    private static Map<String, Object> captureLayoutNode(Object layoutNode, int depth) {
        if (layoutNode == null || depth > 50) { // Prevent infinite recursion
            return null;
        }

        try {
            Map<String, Object> node = new HashMap<>();
            Class<?> nodeClass = layoutNode.getClass();

            // Get basic info
            node.put("id", System.identityHashCode(layoutNode));
            node.put("depth", depth);

            // Get dimensions and position via measureResult or direct properties
            try {
                Method getWidth = nodeClass.getDeclaredMethod("getWidth");
                Method getHeight = nodeClass.getDeclaredMethod("getHeight");
                getWidth.setAccessible(true);
                getHeight.setAccessible(true);
                node.put("width", getWidth.invoke(layoutNode));
                node.put("height", getHeight.invoke(layoutNode));
            } catch (Exception e) {
                // Width/height not available
            }

            // Get position - try multiple approaches
            int[] position = getNodePosition(layoutNode);
            if (position != null) {
                node.put("x", position[0]);
                node.put("y", position[1]);
            }

            // Get composable type/name hints
            String composableName = getComposableName(layoutNode);
            if (composableName != null) {
                node.put("name", composableName);
            }
            node.put("className", "LayoutNode");

            // Try to get semantic info
            Map<String, Object> semantics = extractNodeSemantics(layoutNode);
            if (!semantics.isEmpty()) {
                node.put("semantics", semantics);
            }

            // Get children
            List<Object> children = getLayoutNodeChildren(layoutNode);
            if (!children.isEmpty()) {
                List<Map<String, Object>> childNodes = new ArrayList<>();
                for (Object child : children) {
                    Map<String, Object> childNode = captureLayoutNode(child, depth + 1);
                    if (childNode != null) {
                        childNodes.add(childNode);
                    }
                }
                node.put("children", childNodes);
                node.put("childCount", childNodes.size());
            }

            return node;

        } catch (Exception e) {
            Log.e(TAG, "Error capturing LayoutNode", e);
            return null;
        }
    }

    /**
     * Gets the position of a LayoutNode.
     */
    private static int[] getNodePosition(Object layoutNode) {
        Class<?> nodeClass = layoutNode.getClass();

        try {
            Method getCoordinates = nodeClass.getMethod("getCoordinates");
            Object coords = getCoordinates.invoke(layoutNode);

            if (coords == null) {
                return null;
            }

            // Try positionInRoot (returns Offset - packed floats as long)
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (methodName.contains("positionInRoot") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object result = m.invoke(coords);

                    if (result instanceof Long) {
                        // Offset is packed as floats: (floatToIntBits(x) << 32) | floatToIntBits(y)
                        long packed = (Long) result;
                        float x = Float.intBitsToFloat((int) (packed >>> 32));
                        float y = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
                        if (!Float.isNaN(x) && !Float.isNaN(y)) {
                            return new int[]{Math.round(x), Math.round(y)};
                        }
                    }
                    break;
                }
            }

            // Fallback to positionInWindow
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (methodName.contains("positionInWindow") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object result = m.invoke(coords);

                    if (result instanceof Long) {
                        long packed = (Long) result;
                        float x = Float.intBitsToFloat((int) (packed >>> 32));
                        float y = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
                        if (!Float.isNaN(x) && !Float.isNaN(y)) {
                            return new int[]{Math.round(x), Math.round(y)};
                        }
                    }
                    break;
                }
            }

            // Last resort: getPosition (local position within parent)
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (methodName.startsWith("getPosition") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object result = m.invoke(coords);

                    if (result instanceof Long) {
                        // IntOffset is packed as: (x << 32) | (y & 0xFFFFFFFFL)
                        long packed = (Long) result;
                        int x = (int) (packed >> 32);
                        int y = (int) packed;
                        return new int[]{x, y};
                    }
                    break;
                }
            }
        } catch (Exception e) {
            // Position extraction via coordinates failed
        }

        // Try innerCoordinator.position (IntOffset packed as long)
        try {
            Method getInner = nodeClass.getDeclaredMethod("getInnerCoordinator");
            getInner.setAccessible(true);
            Object coordinator = getInner.invoke(layoutNode);
            if (coordinator != null) {
                for (Method m : coordinator.getClass().getDeclaredMethods()) {
                    if (m.getName().equals("getPosition") && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        Object result = m.invoke(coordinator);
                        if (result instanceof Long) {
                            long packed = (Long) result;
                            int x = (int) (packed >>> 32);
                            int y = (int) packed;
                            return new int[]{x, y};
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Not available
        }

        return null;
    }

    /**
     * Tries to extract the composable name from a LayoutNode.
     */
    private static String getComposableName(Object layoutNode) {
        Class<?> nodeClass = layoutNode.getClass();

        // Try to get measurePolicy for type hints
        try {
            Method getMeasurePolicy = nodeClass.getDeclaredMethod("getMeasurePolicy");
            getMeasurePolicy.setAccessible(true);
            Object policy = getMeasurePolicy.invoke(layoutNode);
            if (policy != null) {
                String policyClass = policy.getClass().getName();
                // Extract meaningful name from policy class
                if (policyClass.contains("$")) {
                    // Lambda in a composable function, extract outer class name
                    String outer = policyClass.substring(0, policyClass.indexOf("$"));
                    int lastDot = outer.lastIndexOf(".");
                    if (lastDot > 0) {
                        String name = outer.substring(lastDot + 1);
                        // Common Compose patterns
                        if (name.endsWith("Kt")) {
                            name = name.substring(0, name.length() - 2);
                        }
                        if (!name.isEmpty() && !name.equals("LayoutNode") && !name.equals("Layout")) {
                            return name;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Not available
        }

        // Try interopViewFactoryHolder for AndroidView
        try {
            Field factoryField = nodeClass.getDeclaredField("interopViewFactoryHolder");
            factoryField.setAccessible(true);
            Object factory = factoryField.get(layoutNode);
            if (factory != null) {
                return "AndroidView";
            }
        } catch (Exception e) {
            // Not an interop view
        }

        return null;
    }

    /**
     * Gets children of a LayoutNode.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> getLayoutNodeChildren(Object layoutNode) {
        List<Object> result = new ArrayList<>();
        Class<?> nodeClass = layoutNode.getClass();

        // Try various methods to get children
        String[] methodsToTry = {
            "getZSortedChildren",   // Newer Compose versions
            "get_children",         // Internal property
            "getChildren$ui_release" // Kotlin internal name mangling
        };

        for (String methodName : methodsToTry) {
            try {
                Method method = nodeClass.getDeclaredMethod(methodName);
                method.setAccessible(true);
                Object children = method.invoke(layoutNode);

                if (children != null) {
                    result = extractChildrenFromCollection(children);
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            } catch (NoSuchMethodException e) {
                // Try next method
            } catch (Exception e) {
                // Method failed, try next
            }
        }

        // Try fields directly
        String[] fieldsToTry = {"_children", "children", "_foldedChildren", "zSortedChildren"};
        for (String fieldName : fieldsToTry) {
            try {
                Field field = nodeClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object children = field.get(layoutNode);

                if (children != null) {
                    result = extractChildrenFromCollection(children);
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            } catch (NoSuchFieldException e) {
                // Try next field
            } catch (Exception e) {
                // Field access failed, try next
            }
        }

        return result;
    }

    /**
     * Extracts children from various collection types.
     */
    private static List<Object> extractChildrenFromCollection(Object collection) {
        List<Object> result = new ArrayList<>();

        if (collection == null) {
            return result;
        }

        // Handle MutableVector (Compose's custom collection)
        String className = collection.getClass().getName();
        if (className.contains("MutableVector") || className.contains("Vector")) {
            try {
                // Try getSize() and get(int)
                Method getSize = collection.getClass().getDeclaredMethod("getSize");
                Method get = collection.getClass().getDeclaredMethod("get", int.class);
                getSize.setAccessible(true);
                get.setAccessible(true);

                int size = (int) getSize.invoke(collection);
                for (int i = 0; i < size; i++) {
                    Object child = get.invoke(collection, i);
                    if (child != null && layoutNodeClass.isInstance(child)) {
                        result.add(child);
                    }
                }
                return result;
            } catch (Exception e) {
                // MutableVector access failed, try other approaches
            }
        }

        // Handle standard Iterable
        if (collection instanceof Iterable) {
            for (Object child : (Iterable<?>) collection) {
                if (child != null && layoutNodeClass.isInstance(child)) {
                    result.add(child);
                }
            }
            return result;
        }

        // Handle List
        if (collection instanceof List) {
            for (Object child : (List<?>) collection) {
                if (child != null && layoutNodeClass.isInstance(child)) {
                    result.add(child);
                }
            }
            return result;
        }

        // Handle array
        if (collection.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(collection);
            for (int i = 0; i < length; i++) {
                Object child = java.lang.reflect.Array.get(collection, i);
                if (child != null && layoutNodeClass.isInstance(child)) {
                    result.add(child);
                }
            }
            return result;
        }

        return result;
    }

    /**
     * Extracts semantics information from a LayoutNode.
     */
    private static Map<String, Object> extractNodeSemantics(Object layoutNode) {
        Map<String, Object> semantics = new HashMap<>();

        try {
            // Try to get semantics configuration
            Method getSemantics = layoutNode.getClass().getDeclaredMethod("getCollapsedSemantics");
            getSemantics.setAccessible(true);
            Object config = getSemantics.invoke(layoutNode);

            if (config != null) {
                // Extract key semantic properties
                extractSemanticsProperties(config, semantics);
            }
        } catch (NoSuchMethodException e) {
            // Try alternative method name
            try {
                Method getSemantics = layoutNode.getClass().getDeclaredMethod("getSemanticsConfiguration");
                getSemantics.setAccessible(true);
                Object config = getSemantics.invoke(layoutNode);

                if (config != null) {
                    extractSemanticsProperties(config, semantics);
                }
            } catch (Exception e2) {
                // No semantics
            }
        } catch (Exception e) {
            // No semantics
        }

        return semantics;
    }

    /**
     * Extracts properties from a SemanticsConfiguration.
     */
    private static void extractSemanticsProperties(Object config, Map<String, Object> result) {
        if (config == null) return;

        Class<?> configClass = config.getClass();

        // Try to access the internal props map directly
        try {
            for (Field field : configClass.getDeclaredFields()) {
                String fieldName = field.getName();
                if (fieldName.contains("props") || fieldName.contains("map") || fieldName.contains("Map")) {
                    field.setAccessible(true);
                    Object propsMap = field.get(config);
                    if (propsMap instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) propsMap;
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            Object key = entry.getKey();
                            Object value = entry.getValue();
                            if (key != null && value != null) {
                                // Skip AccessibilityAction values - these are actions, not properties
                                String valueClassName = value.getClass().getName();
                                if (valueClassName.contains("AccessibilityAction")) {
                                    continue;
                                }

                                // Match specific property keys
                                String propertyName = getPropertyKeyName(key);

                                if (propertyName != null) {
                                    if (propertyName.equals("Text")) {
                                        String extractedValue = extractTextValue(value);
                                        if (extractedValue != null && !extractedValue.isEmpty()) {
                                            result.put("text", extractedValue);
                                        }
                                    } else if (propertyName.equals("Role")) {
                                        result.put("role", extractTextValue(value));
                                    } else if (propertyName.equals("ContentDescription")) {
                                        result.put("contentDescription", extractTextValue(value));
                                    } else if (propertyName.equals("TestTag")) {
                                        result.put("testTag", extractTextValue(value));
                                    } else if (propertyName.equals("EditableText")) {
                                        result.put("editableText", extractTextValue(value));
                                    } else if (propertyName.equals("StateDescription")) {
                                        result.put("stateDescription", extractTextValue(value));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Props map access failed, try other approaches
        }

        try {
            // Try to get text content via getOrNull with SemanticsProperties.Text key
            // First, try direct property access methods
            for (Method m : configClass.getMethods()) {
                String name = m.getName();
                if (m.getParameterCount() == 0) {
                    try {
                        if (name.equals("getText") || name.contains("Text") && name.startsWith("get")) {
                            Object value = m.invoke(config);
                            if (value != null) {
                                result.put("text", extractTextValue(value));
                            }
                        } else if (name.contains("ContentDescription") && name.startsWith("get")) {
                            Object value = m.invoke(config);
                            if (value != null) {
                                result.put("contentDescription", value.toString());
                            }
                        } else if (name.contains("Role") && name.startsWith("get")) {
                            Object value = m.invoke(config);
                            if (value != null) {
                                result.put("role", value.toString());
                            }
                        } else if (name.contains("TestTag") && name.startsWith("get")) {
                            Object value = m.invoke(config);
                            if (value != null) {
                                result.put("testTag", value.toString());
                            }
                        }
                    } catch (Exception e) {
                        // Method failed, continue
                    }
                }
            }

            // Try iterator approach as fallback
            try {
                Method iterator = configClass.getMethod("iterator");
                Object iter = iterator.invoke(config);

                if (iter != null) {
                    Method hasNext = iter.getClass().getMethod("hasNext");
                    Method next = iter.getClass().getMethod("next");

                    int count = 0;
                    while ((Boolean) hasNext.invoke(iter) && count < 50) {
                        count++;
                        Object entry = next.invoke(iter);

                        if (entry != null) {
                            // Entry is a Map.Entry<SemanticsPropertyKey, ?>
                            try {
                                Method getKey = entry.getClass().getMethod("getKey");
                                Method getValue = entry.getClass().getMethod("getValue");
                                getKey.setAccessible(true);
                                getValue.setAccessible(true);

                                Object key = getKey.invoke(entry);
                                Object value = getValue.invoke(entry);

                                if (key != null && value != null) {
                                    // Skip AccessibilityAction values
                                    String valueClassName = value.getClass().getName();
                                    if (valueClassName.contains("AccessibilityAction")) {
                                        continue;
                                    }

                                    String propertyName = getPropertyKeyName(key);

                                    if (propertyName != null) {
                                        if (propertyName.equals("Text")) {
                                            String extractedValue = extractTextValue(value);
                                            if (extractedValue != null && !extractedValue.isEmpty()) {
                                                result.put("text", extractedValue);
                                            }
                                        } else if (propertyName.equals("ContentDescription")) {
                                            result.put("contentDescription", extractTextValue(value));
                                        } else if (propertyName.equals("Role")) {
                                            result.put("role", extractTextValue(value));
                                        } else if (propertyName.equals("TestTag")) {
                                            result.put("testTag", extractTextValue(value));
                                        } else if (propertyName.equals("Heading")) {
                                            result.put("heading", true);
                                        } else if (propertyName.equals("Disabled")) {
                                            result.put("disabled", true);
                                        } else if (propertyName.equals("Selected")) {
                                            result.put("selected", value);
                                        } else if (propertyName.equals("Focused")) {
                                            result.put("focused", value);
                                        } else if (propertyName.equals("EditableText")) {
                                            result.put("editableText", extractTextValue(value));
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Skip this entry
                            }
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                // No iterator method
            }

        } catch (Exception e) {
            Log.e(TAG, "Error extracting semantic properties: " + e.getMessage());
        }
    }

    /**
     * Extracts the name from a SemanticsPropertyKey.
     */
    private static String getPropertyKeyName(Object key) {
        if (key == null) return null;

        try {
            // Try to get the 'name' field from SemanticsPropertyKey
            Field nameField = key.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            Object name = nameField.get(key);
            if (name != null) {
                return name.toString();
            }
        } catch (NoSuchFieldException e) {
            // Try methods
            try {
                Method getName = key.getClass().getDeclaredMethod("getName");
                getName.setAccessible(true);
                Object name = getName.invoke(key);
                if (name != null) {
                    return name.toString();
                }
            } catch (Exception e2) {
                // Fall through to toString parsing
            }
        } catch (Exception e) {
            // Fall through
        }

        // Parse from toString() - format is usually "SemanticsPropertyKey: PropertyName"
        String keyStr = key.toString();
        if (keyStr.contains(":")) {
            return keyStr.substring(keyStr.lastIndexOf(":") + 1).trim();
        }

        return keyStr;
    }

    /**
     * Extracts text from various Compose text representations.
     */
    private static String extractTextValue(Object value) {
        if (value == null) return null;

        String className = value.getClass().getName();

        // Handle AnnotatedString
        if (className.contains("AnnotatedString")) {
            try {
                Method getText = value.getClass().getMethod("getText");
                return getText.invoke(value).toString();
            } catch (Exception e) {
                return value.toString();
            }
        }

        // Handle List<AnnotatedString> (common for Text semantics)
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (!list.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(extractTextValue(item));
                }
                return sb.toString();
            }
        }

        return value.toString();
    }

    /**
     * Captures the semantics tree from a ComposeView.
     */
    private static Map<String, Object> captureSemanticsTree(Object composeView) {
        try {
            Map<String, Object> result = new HashMap<>();

            // Get SemanticsOwner
            Method getSemanticsOwner = androidComposeViewClass.getDeclaredMethod("getSemanticsOwner");
            getSemanticsOwner.setAccessible(true);
            Object semanticsOwner = getSemanticsOwner.invoke(composeView);

            if (semanticsOwner != null) {
                // Get root semantics node
                Method getRootNode = semanticsOwner.getClass().getDeclaredMethod("getRootSemanticsNode");
                getRootNode.setAccessible(true);
                Object rootNode = getRootNode.invoke(semanticsOwner);

                if (rootNode != null) {
                    result.put("root", captureSemanticsNode(rootNode, 0));
                }
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error capturing semantics tree", e);
            return null;
        }
    }

    /**
     * Recursively captures a SemanticsNode.
     */
    private static Map<String, Object> captureSemanticsNode(Object semanticsNode, int depth) {
        if (semanticsNode == null || depth > 50) {
            return null;
        }

        try {
            Map<String, Object> node = new HashMap<>();

            // Get node ID
            Method getId = semanticsNode.getClass().getDeclaredMethod("getId");
            node.put("id", getId.invoke(semanticsNode));

            // Get bounds
            try {
                Method getBoundsInRoot = semanticsNode.getClass().getDeclaredMethod("getBoundsInRoot");
                Object bounds = getBoundsInRoot.invoke(semanticsNode);
                if (bounds != null) {
                    node.put("bounds", bounds.toString());
                }
            } catch (Exception e) {
                // Bounds not available
            }

            // Get config
            try {
                Method getConfig = semanticsNode.getClass().getMethod("getConfig");
                Object config = getConfig.invoke(semanticsNode);
                if (config != null) {
                    Map<String, Object> props = new HashMap<>();
                    extractSemanticsProperties(config, props);
                    if (!props.isEmpty()) {
                        node.putAll(props);
                    }
                }
            } catch (Exception e) {
                // Config extraction failed
            }

            // Get children
            try {
                Method getChildren = semanticsNode.getClass().getDeclaredMethod("getChildren");
                Object children = getChildren.invoke(semanticsNode);

                if (children instanceof Iterable) {
                    List<Map<String, Object>> childNodes = new ArrayList<>();
                    for (Object child : (Iterable<?>) children) {
                        Map<String, Object> childNode = captureSemanticsNode(child, depth + 1);
                        if (childNode != null) {
                            childNodes.add(childNode);
                        }
                    }
                    if (!childNodes.isEmpty()) {
                        node.put("children", childNodes);
                    }
                }
            } catch (Exception e) {
                // No children
            }

            return node;

        } catch (Exception e) {
            Log.e(TAG, "Error capturing SemanticsNode", e);
            return null;
        }
    }
}
