package io.yamsergey.dta.sidekick.compose;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.util.DisplayMetrics;
import io.yamsergey.dta.sidekick.SidekickLog;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

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
    private static boolean inspectionEnabled = false;

    /**
     * Enables Compose inspection mode by setting isDebugInspectorInfoEnabled = true.
     * This must be called BEFORE any Compose UI is created to have effect.
     *
     * <p>When enabled, Compose will populate the inspection_slot_table_set tag
     * on ComposeViews with CompositionData, allowing access to actual composable
     * names from compiler-embedded sourceInfo metadata.</p>
     *
     * <p>This mirrors what Android Studio's Layout Inspector does when connecting.</p>
     *
     * @return true if inspection was enabled successfully, false otherwise
     */
    public static boolean enableInspection() {
        if (inspectionEnabled) {
            SidekickLog.d(TAG, "Inspection already enabled");
            return true;
        }

        try {
            // The flag is: androidx.compose.ui.platform.InspectableValueKt.isDebugInspectorInfoEnabled
            // It's a top-level mutable property in Kotlin, compiled to a static field
            Class<?> inspectableValueClass = Class.forName("androidx.compose.ui.platform.InspectableValueKt");

            // Try to find the setter method first (Kotlin property setter)
            boolean success = false;
            for (Method m : inspectableValueClass.getDeclaredMethods()) {
                if (m.getName().contains("setDebugInspectorInfoEnabled") ||
                    m.getName().contains("isDebugInspectorInfoEnabled")) {
                    SidekickLog.d(TAG, "Found method: " + m.getName() + " params: " + m.getParameterCount());
                }
            }

            // Try setter method
            try {
                Method setter = inspectableValueClass.getDeclaredMethod("setIsDebugInspectorInfoEnabled", boolean.class);
                setter.setAccessible(true);
                setter.invoke(null, true);
                success = true;
                SidekickLog.i(TAG, "Enabled inspection via setIsDebugInspectorInfoEnabled()");
            } catch (NoSuchMethodException e) {
                SidekickLog.d(TAG, "setIsDebugInspectorInfoEnabled not found, trying field access");
            }

            // Try direct field access if setter not available
            if (!success) {
                for (Field f : inspectableValueClass.getDeclaredFields()) {
                    if (f.getName().contains("isDebugInspectorInfoEnabled") ||
                        f.getName().contains("DebugInspectorInfo")) {
                        SidekickLog.d(TAG, "Found field: " + f.getName() + " type: " + f.getType().getName());
                        f.setAccessible(true);
                        f.set(null, true);
                        success = true;
                        SidekickLog.i(TAG, "Enabled inspection via field: " + f.getName());
                        break;
                    }
                }
            }

            if (success) {
                inspectionEnabled = true;
                SidekickLog.i(TAG, "Compose inspection mode ENABLED - CompositionData will be available");
                return true;
            } else {
                SidekickLog.w(TAG, "Could not find isDebugInspectorInfoEnabled property");
                // List all fields and methods for debugging
                SidekickLog.d(TAG, "Available fields in InspectableValueKt:");
                for (Field f : inspectableValueClass.getDeclaredFields()) {
                    SidekickLog.d(TAG, "  Field: " + f.getName() + " (" + f.getType().getName() + ")");
                }
                SidekickLog.d(TAG, "Available methods in InspectableValueKt:");
                for (Method m : inspectableValueClass.getDeclaredMethods()) {
                    SidekickLog.d(TAG, "  Method: " + m.getName());
                }
            }

        } catch (ClassNotFoundException e) {
            SidekickLog.w(TAG, "InspectableValueKt class not found - app may not use Compose UI", e);
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to enable inspection mode", e);
        }

        return false;
    }

    /**
     * Checks if inspection mode is enabled.
     *
     * @return true if inspection mode was successfully enabled
     */
    public static boolean isInspectionEnabled() {
        return inspectionEnabled;
    }

    /**
     * Captures the full Compose UI hierarchy.
     *
     * @return Map representing the hierarchy, or null if no Compose views found
     */
    public static Map<String, Object> captureHierarchy() {
        ensureInitialized();

        List<Object> composeViews = findComposeViews();
        if (composeViews.isEmpty()) {
            SidekickLog.w(TAG, "No Compose views found");
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
     * Captures a unified tree combining layout hierarchy with semantic information.
     * This provides a clean, source-like representation of the Compose UI.
     *
     * @return Map representing the unified tree, or null if no Compose views found
     */
    public static Map<String, Object> captureUnifiedTree() {
        ensureInitialized();

        Activity activity = getCurrentActivity();
        if (activity == null) {
            SidekickLog.w(TAG, "No current activity found");
            return null;
        }

        List<Object> composeViews = findComposeViews();
        if (composeViews.isEmpty()) {
            SidekickLog.w(TAG, "No Compose views found");
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "compose_tree");
        result.put("timestamp", System.currentTimeMillis());

        // Screen info
        Map<String, Object> screen = new HashMap<>();
        screen.put("activity", activity.getClass().getSimpleName());

        // Add window dimensions (matches screenshot capture dimensions)
        Window window = activity.getWindow();
        if (window != null && window.getDecorView() != null) {
            View decorView = window.getDecorView();
            screen.put("windowWidth", decorView.getWidth());
            screen.put("windowHeight", decorView.getHeight());

            // Get decorView's position on screen - needed to convert screen coords to window coords
            // The bounds from localToScreen are in absolute screen coordinates, but the screenshot
            // captures from decorView which may be offset from screen origin (e.g., status bar)
            int[] windowPos = new int[2];
            decorView.getLocationOnScreen(windowPos);
            screen.put("windowOffsetX", windowPos[0]);
            screen.put("windowOffsetY", windowPos[1]);
        }

        // Add display metrics (device screen resolution)
        DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
        screen.put("screenWidth", displayMetrics.widthPixels);
        screen.put("screenHeight", displayMetrics.heightPixels);
        screen.put("density", displayMetrics.density);

        result.put("screen", screen);

        // When there are multiple ComposeViews, we need to merge all of them
        // Each ComposeView might represent a different layer (background, content, overlays)
        List<Map<String, Object>> allRoots = new ArrayList<>();
        
        for (int i = 0; i < composeViews.size(); i++) {
            Object composeView = composeViews.get(i);
            SidekickLog.d(TAG, "Processing ComposeView [" + i + "]: " + composeView);

            Object rootLayoutNode = getRootLayoutNode(composeView);

            if (rootLayoutNode == null) {
                SidekickLog.d(TAG, "  -> Skipping: rootLayoutNode is null");
                continue;
            }

            // Check if this root has any children
            List<Object> children = getLayoutNodeChildren(rootLayoutNode);
            if (children.isEmpty()) {
                SidekickLog.d(TAG, "  -> Skipping: no children in rootLayoutNode");
                continue;
            }

            SidekickLog.d(TAG, "  -> Processing: has " + children.size() + " children");

            // Get the window's screen position for coordinate conversion.
            // boundsInWindow() returns coordinates relative to the window origin,
            // so we need the window's screen position, not the view's.
            // Window screen position = view screen position - view window position
            int[] windowOffset = new int[2];
            if (composeView instanceof View) {
                View view = (View) composeView;
                int[] screenLoc = new int[2];
                int[] windowLoc = new int[2];
                view.getLocationOnScreen(screenLoc);
                view.getLocationInWindow(windowLoc);
                windowOffset[0] = screenLoc[0] - windowLoc[0];
                windowOffset[1] = screenLoc[1] - windowLoc[1];
                SidekickLog.d(TAG, "  Window offset: screen=" + screenLoc[0] + "," + screenLoc[1] +
                      " inWindow=" + windowLoc[0] + "," + windowLoc[1] +
                      " -> windowScreen=" + windowOffset[0] + "," + windowOffset[1]);
            }
            
            // Build semantics and composable info maps for this ComposeView
            Map<Integer, Map<String, Object>> semanticsById = buildSemanticsById(composeView);
            Map<Integer, ComposableInfo> composableInfoMap = buildComposableInfoMap(composeView);
            
            // Capture this ComposeView's tree
            Map<String, Object> rootNode = captureUnifiedNode(rootLayoutNode, windowOffset[0], windowOffset[1], 0, semanticsById, composableInfoMap, null);
            if (rootNode != null) {
                allRoots.add(rootNode);
            }
        }
        
        // Create a synthetic root that contains all ComposeView roots as children
        if (!allRoots.isEmpty()) {
            Map<String, Object> syntheticRoot = new HashMap<>();
            syntheticRoot.put("composable", "Root");
            syntheticRoot.put("children", allRoots);
            syntheticRoot.put("id", "0_synthetic");
            result.put("root", syntheticRoot);
        }

        return result;
    }

    /**
     * Builds a map from semanticsId to semantic properties using the Android Studio approach.
     *
     * <p>This uses SemanticsOwner.getAllSemanticsNodes() to get all semantics nodes,
     * then maps each node's stable integer ID to its properties. This is much more
     * reliable than the old approach of mapping via System.identityHashCode.</p>
     *
     * <p>The semanticsId on SemanticsNode matches the semanticsId on LayoutInfo,
     * providing a stable link between layout and semantics.</p>
     *
     * @param composeView The AndroidComposeView to extract semantics from
     * @return Map from semanticsId (Integer) to semantic properties (Map)
     */
    private static Map<Integer, Map<String, Object>> buildSemanticsById(Object composeView) {
        Map<Integer, Map<String, Object>> result = new HashMap<>();

        try {
            Method getSemanticsOwner = androidComposeViewClass.getDeclaredMethod("getSemanticsOwner");
            getSemanticsOwner.setAccessible(true);
            Object semanticsOwner = getSemanticsOwner.invoke(composeView);

            if (semanticsOwner == null) {
                SidekickLog.w(TAG, "SemanticsOwner is null");
                return result;
            }

            // Get all semantics nodes using getAllSemanticsNodes(mergingEnabled = false)
            // This gives us unmerged semantics where each node has its own properties
            List<Object> allNodes = getAllSemanticsNodes(semanticsOwner, false);
            SidekickLog.d(TAG, "Found " + allNodes.size() + " semantics nodes via getAllSemanticsNodes");

            for (Object semanticsNode : allNodes) {
                try {
                    // Get the stable semantics ID
                    int semanticsId = getSemanticsNodeId(semanticsNode);
                    if (semanticsId == -1) continue;

                    // Extract semantic properties
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("semanticsId", semanticsId);

                    Method getConfig = semanticsNode.getClass().getMethod("getConfig");
                    Object config = getConfig.invoke(semanticsNode);
                    if (config != null) {
                        extractSemanticsProperties(config, entry);
                    }

                    // Only store if we have meaningful content
                    if (entry.containsKey("text") || entry.containsKey("role") ||
                        entry.containsKey("contentDescription") || entry.containsKey("testTag")) {
                        result.put(semanticsId, entry);
                        SidekickLog.d(TAG, "Mapped semanticsId " + semanticsId + " -> " + entry);
                    }
                } catch (Exception e) {
                    SidekickLog.e(TAG, "Error processing semantics node: " + e.getMessage());
                }
            }

            SidekickLog.d(TAG, "Built semantics map with " + result.size() + " entries");

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error building semantics by ID", e);
        }

        return result;
    }

    /**
     * Holds composable information extracted from CompositionData.
     */
    private static class ComposableInfo {
        String name;        // e.g., "Text", "Button", "Column"
        String fileName;    // e.g., "HomeScreen.kt"
        int lineNumber;     // Source line number
        String packageName; // e.g., "com.example.ui"
        boolean isLibraryComposable; // true for CC(...) prefix, false for C(...)
    }

    /**
     * Builds a map from LayoutNode identity to ComposableInfo using CompositionData.
     * This is the Android Studio approach for getting actual composable names
     * from compiler-embedded metadata (sourceInfo).
     *
     * @param composeView The AndroidComposeView
     * @return Map from LayoutNode identity hashcode to ComposableInfo
     */
    private static Map<Integer, ComposableInfo> buildComposableInfoMap(Object composeView) {
        Map<Integer, ComposableInfo> result = new HashMap<>();
        groupLogCount = 0; // Reset log counter for this capture

        try {
            // Get ALL CompositionData from the inspection tag (it's a Set of SlotTables)
            java.util.Set<?> compositionDataSet = getAllCompositionData(composeView);

            if (compositionDataSet != null && !compositionDataSet.isEmpty()) {
                // Walk each SlotTable/CompositionData and extract info
                for (Object compositionData : compositionDataSet) {
                    walkCompositionGroups(compositionData, result);
                }
                SidekickLog.d(TAG, "Built composable info map with " + result.size() + " entries from " + compositionDataSet.size() + " SlotTables");
            } else {
                SidekickLog.d(TAG, "CompositionData not available, will use fallback naming");
            }

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error building composable info map: " + e.getMessage());
        }

        return result;
    }

    /**
     * Gets ALL CompositionData objects from the AndroidComposeView via the inspection tag.
     * The tag contains a Set of SlotTable/CompositionData objects.
     */
    private static java.util.Set<?> getAllCompositionData(Object composeView) {
        try {
            if (!(composeView instanceof View)) {
                return null;
            }
            View view = (View) composeView;

            int tagId = findInspectionTagId();
            if (tagId != 0) {
                Object tag = view.getTag(tagId);
                if (tag instanceof java.util.Set) {
                    java.util.Set<?> set = (java.util.Set<?>) tag;
                    SidekickLog.d(TAG, "Found " + set.size() + " CompositionData entries in inspection tag");
                    return set;
                }
            }
        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting CompositionData set: " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets CompositionData from the AndroidComposeView via the inspection tag.
     * The tag ID is R.id.inspection_slot_table_set from compose-runtime.
     */
    private static Object getCompositionData(Object composeView) {
        try {
            if (!(composeView instanceof View)) {
                SidekickLog.d(TAG, "getCompositionData: composeView is not a View");
                return null;
            }
            View view = (View) composeView;

            // Try to find the inspection_slot_table_set tag ID
            // It's defined in compose-runtime resources
            int tagId = findInspectionTagId();
            SidekickLog.d(TAG, "getCompositionData: tagId = " + tagId);

            if (tagId != 0) {
                Object tag = view.getTag(tagId);
                SidekickLog.d(TAG, "getCompositionData: tag at " + tagId + " = " + (tag != null ? tag.getClass().getName() : "null"));
                if (tag != null) {
                    SidekickLog.d(TAG, "Found CompositionData via tag ID " + tagId + ": " + tag.getClass().getName());
                    // It's a Set<CompositionData>, return the first one
                    if (tag instanceof java.util.Set) {
                        java.util.Set<?> set = (java.util.Set<?>) tag;
                        SidekickLog.d(TAG, "CompositionData Set size: " + set.size());
                        if (!set.isEmpty()) {
                            Object first = set.iterator().next();
                            SidekickLog.d(TAG, "CompositionData first element: " + first.getClass().getName());
                            return first;
                        } else {
                            SidekickLog.d(TAG, "CompositionData Set is EMPTY - composition may not have run yet");
                        }
                    }
                    return tag;
                }
            }

            // Try to enumerate all tags on the view to find CompositionData
            SidekickLog.d(TAG, "Trying to find CompositionData via other means...");

            // Fallback: try to find CompositionData via reflection on the view
            for (Method m : composeView.getClass().getMethods()) {
                if (m.getName().contains("getComposition") && m.getParameterCount() == 0) {
                    SidekickLog.d(TAG, "Found method: " + m.getName());
                    m.setAccessible(true);
                    Object composition = m.invoke(composeView);
                    if (composition != null) {
                        SidekickLog.d(TAG, "Got composition: " + composition.getClass().getName());
                        // Try to get CompositionData from Composition
                        for (Method cm : composition.getClass().getMethods()) {
                            if (cm.getName().contains("getData") || cm.getName().contains("getCompositionData")) {
                                SidekickLog.d(TAG, "Found data method: " + cm.getName());
                                cm.setAccessible(true);
                                return cm.invoke(composition);
                            }
                        }
                        // List all methods on composition for debugging
                        SidekickLog.d(TAG, "Composition methods:");
                        for (Method cm : composition.getClass().getMethods()) {
                            if (!cm.getDeclaringClass().equals(Object.class)) {
                                SidekickLog.d(TAG, "  " + cm.getName() + " -> " + cm.getReturnType().getSimpleName());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting CompositionData: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Finds the R.id.inspection_slot_table_set tag ID.
     */
    private static int findInspectionTagId() {
        // Try various package names where the tag might be defined
        String[] packages = {
            "androidx.compose.runtime.R$id",
            "androidx.compose.ui.R$id",
            "androidx.compose.ui.tooling.R$id"
        };

        for (String pkg : packages) {
            try {
                Class<?> rId = Class.forName(pkg);
                SidekickLog.d(TAG, "Found R class: " + pkg);
                // List all fields for debugging
                for (Field f : rId.getFields()) {
                    if (f.getName().contains("inspection") || f.getName().contains("slot")) {
                        SidekickLog.d(TAG, "  Found field: " + f.getName() + " = " + f.getInt(null));
                    }
                }
                Field field = rId.getField("inspection_slot_table_set");
                int id = field.getInt(null);
                SidekickLog.d(TAG, "Found inspection_slot_table_set in " + pkg + ": " + id);
                return id;
            } catch (ClassNotFoundException e) {
                SidekickLog.d(TAG, "R class not found: " + pkg);
            } catch (NoSuchFieldException e) {
                SidekickLog.d(TAG, "Field inspection_slot_table_set not found in " + pkg);
            } catch (Exception e) {
                // Try next
            }
        }

        return 0;
    }

    /**
     * Walks CompositionData.compositionGroups recursively to extract composable info.
     * Uses ancestorInfo to carry forward sourceInfo from parent groups to nodes.
     */
    private static void walkCompositionGroups(Object compositionData, Map<Integer, ComposableInfo> result) {
        walkCompositionGroupsWithAncestors(compositionData, result, null, null);
    }

    /**
     * Walks CompositionData.compositionGroups recursively, tracking both immediate ancestor
     * and closest user composable ancestor (which survives through library composable chains).
     *
     * @param compositionData The CompositionData to walk
     * @param result Map to populate with LayoutNode -> ComposableInfo mappings
     * @param immediateAncestor The composable info from the immediate parent group
     * @param userAncestor The closest user-defined composable in the ancestry chain
     */
    private static void walkCompositionGroupsWithAncestors(Object compositionData, Map<Integer, ComposableInfo> result,
                                                           ComposableInfo immediateAncestor, ComposableInfo userAncestor) {
        try {
            // Get compositionGroups iterable
            Method getGroups = null;
            for (Method m : compositionData.getClass().getMethods()) {
                String name = m.getName();
                if (name.equals("getCompositionGroups") && m.getParameterCount() == 0) {
                    getGroups = m;
                    break;
                }
            }

            if (getGroups == null) {
                return;
            }

            getGroups.setAccessible(true);
            Object groups = getGroups.invoke(compositionData);

            if (groups instanceof Iterable) {
                for (Object group : (Iterable<?>) groups) {
                    processCompositionGroupWithAncestors(group, result, immediateAncestor, userAncestor);
                }
            }

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error walking composition groups: " + e.getMessage());
        }
    }

    // Counter to limit excessive logging
    private static int groupLogCount = 0;
    private static final int MAX_GROUP_LOGS = 20;

    // Internal/infrastructure composable names to skip
    private static final java.util.Set<String> INTERNAL_COMPOSABLES = new java.util.HashSet<>(java.util.Arrays.asList(
        // Core runtime internals
        "ReusableComposeNode", "remember", "rememberComposableLambda",
        "Layout", "Subcomposition", "SubcomposeLayout",
        "ReusableContent", "ReusableContentHost",
        // Effect handlers
        "DisposableEffect", "LaunchedEffect", "SideEffect",
        // State handlers
        "CompositionLocalProvider",
        // Lazy internals
        "SkippableItem", "Item", "LazyLayoutPinnableItem",
        // Saveable internals
        "SaveableStateProvider",
        // Measure policies (internal implementations)
        "columnMeasurePolicy", "rowMeasurePolicy", "boxMeasurePolicy",
        // Card internals
        "cardColors", "cardElevation", "shadowElevation",
        "surfaceColorAtElevation", "applyTonalElevation"
    ));

    // Known Compose library source files (Material, Foundation, etc.)
    private static final java.util.Set<String> LIBRARY_SOURCE_FILES = new java.util.HashSet<>(java.util.Arrays.asList(
        // Material components
        "Card.kt", "Surface.kt", "Button.kt", "Scaffold.kt", "TopAppBar.kt",
        "BottomSheet.kt", "Dialog.kt", "Drawer.kt", "Snackbar.kt",
        "TextField.kt", "OutlinedTextField.kt", "Checkbox.kt", "Switch.kt",
        "RadioButton.kt", "Slider.kt", "Tab.kt", "NavigationBar.kt",
        "FloatingActionButton.kt", "Icon.kt", "Text.kt", "Image.kt",
        "Divider.kt", "CircularProgressIndicator.kt", "LinearProgressIndicator.kt",
        "AlertDialog.kt", "DropdownMenu.kt", "ModalBottomSheet.kt",
        "ColorScheme.kt", "Typography.kt", "Shapes.kt", "Theme.kt",
        // Foundation components
        "Box.kt", "Column.kt", "Row.kt", "Spacer.kt", "BasicText.kt",
        "LazyColumn.kt", "LazyRow.kt", "LazyGrid.kt", "Layout.kt",
        "Canvas.kt", "Image.kt", "Clickable.kt", "Scrollable.kt",
        "Pager.kt", "BasicTextField.kt", "Selection.kt",
        // Animation
        "AnimatedContent.kt", "AnimatedVisibility.kt", "Crossfade.kt",
        "AnimatedEnterExitImpl.kt",
        // Navigation
        "NavHost.kt", "NavGraph.kt", "NavBackStack.kt",
        // Internal
        "ComposableLambda.kt", "Composables.kt", "Effects.kt",
        "CompositionLocal.kt", "SaveableStateHolder.kt",
        "LazySaveableStateHolder.kt", "LazyLayoutItemContentFactory.kt",
        "LazyListItemProvider.kt", "LazyLayoutPinnableItem.kt",
        "ProvideContentColorTextStyle.kt"
    ));

    // Composables to skip entirely in the tree (promote their children to parent)
    // These are internal wrappers that add no value to the user-facing tree
    private static final java.util.Set<String> SKIP_COMPOSABLES = new java.util.HashSet<>(java.util.Arrays.asList(
        // Animation internal wrappers
        "AnimatedEnterExitImpl", "AnimatedContentScope",
        // State/composition holders
        "LocalOwnersProvider", "LazySaveableStateHolderProvider",
        "LazyLayoutPinnableItem", "SaveableStateProvider",
        // Navigation internals
        "NavBackStackEntryProvider",
        // Content styling wrappers
        "ProvideContentColorTextStyle", "ProvideTextStyle",
        // Lazy layout internals
        "LazyLayoutItemContentFactory", "LazyListItemProvider"
    ));

    // Mapping of parent composables to child composables that should be collapsed
    // When a parent has only these children, collapse them and promote grandchildren
    private static final java.util.Map<String, java.util.Set<String>> COLLAPSE_CHILDREN;
    static {
        COLLAPSE_CHILDREN = new java.util.HashMap<>();
        // Button's internal Row should be hidden - promote its children (Text) directly
        COLLAPSE_CHILDREN.put("Button", new java.util.HashSet<>(java.util.Arrays.asList("Row")));
        // Card's internal Surface/Box should be hidden
        COLLAPSE_CHILDREN.put("Card", new java.util.HashSet<>(java.util.Arrays.asList("Surface", "Box")));
        // Surface's internal Box should be hidden
        COLLAPSE_CHILDREN.put("Surface", new java.util.HashSet<>(java.util.Arrays.asList("Box")));
    }

    /**
     * Normalizes composable names for cleaner display.
     * Maps internal names to user-friendly names.
     */
    private static String normalizeComposableName(String name) {
        if (name == null) return null;
        switch (name) {
            case "BasicText": return "Text";
            case "BasicTextField": return "TextField";
            case "LazyLayout": return "LazyColumn"; // Common case - could also be LazyRow
            default: return name;
        }
    }

    /**
     * Checks if a composable name represents a user-level composable.
     * Filters out internal Compose runtime and foundation infrastructure.
     */
    private static boolean isUserLevelComposable(String name) {
        if (name == null || name.isEmpty()) return false;
        // Skip internal names
        if (INTERNAL_COMPOSABLES.contains(name)) return false;
        // Skip names that start with lowercase (likely helper/internal functions)
        if (Character.isLowerCase(name.charAt(0))) return false;
        return true;
    }

    /**
     * Processes a single CompositionGroup to extract composable info.
     * Tracks both immediate ancestor and the closest user composable ancestor.
     *
     * @param group The CompositionGroup to process
     * @param result Map to populate with LayoutNode -> ComposableInfo mappings
     * @param immediateAncestor The composable info from the immediate parent group
     * @param userAncestor The closest user-defined composable in the ancestry chain (survives through library chains)
     */
    private static void processCompositionGroupWithAncestors(Object group, Map<Integer, ComposableInfo> result,
                                                             ComposableInfo immediateAncestor, ComposableInfo userAncestor) {
        try {
            // Get sourceInfo - contains compiler metadata like "C(Text)P(...)@file.kt:42"
            String sourceInfo = null;
            ComposableInfo thisGroupInfo = null; // Info for this specific group

            try {
                Method getSourceInfo = group.getClass().getMethod("getSourceInfo");
                getSourceInfo.setAccessible(true);
                Object info = getSourceInfo.invoke(group);
                if (info != null) {
                    sourceInfo = info.toString();
                    // Parse this group's sourceInfo
                    ComposableInfo parsed = parseSourceInfo(sourceInfo);
                    if (parsed.name != null) {
                        // Log composable names for debugging
                        if (groupLogCount < 100) {
                            String type = parsed.isLibraryComposable ? "library" : "user";
                            SidekickLog.d(TAG, "Found composable: " + parsed.name + " (" + type + ") from " + sourceInfo);
                        }

                        if (isUserLevelComposable(parsed.name)) {
                            thisGroupInfo = parsed;
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                // sourceInfo not available
            }

            // Get the node (LayoutInfo/LayoutNode) associated with this group
            Object node = null;
            try {
                Method getNode = group.getClass().getMethod("getNode");
                getNode.setAccessible(true);
                node = getNode.invoke(group);
            } catch (NoSuchMethodException e) {
                // node not available
            }

            // Update userAncestor if this is a user composable
            ComposableInfo newUserAncestor = userAncestor;
            if (thisGroupInfo != null && !thisGroupInfo.isLibraryComposable) {
                newUserAncestor = thisGroupInfo;
            }

            // Determine what to use for THIS node
            // Key insight: user composables (MainContent) don't create LayoutNodes directly,
            // they wrap library composables (Column) which have the actual node.
            // So when a library composable has a node and there's a user ancestor,
            // the node should be labeled with the USER composable (MainContent "owns" that Column).
            ComposableInfo infoForNode;

            // Track whether we use userAncestor for this node (to "consume" it)
            boolean usedUserAncestor = false;

            if (thisGroupInfo != null) {
                if (thisGroupInfo.isLibraryComposable && newUserAncestor != null) {
                    // Library composable (Column) with user ancestor (MainContent)
                    // Node label should be the user composable (MainContent owns this layout)
                    infoForNode = newUserAncestor;
                    usedUserAncestor = true;
                } else {
                    // User composable or library without user ancestor - use as-is
                    infoForNode = thisGroupInfo;
                }
            } else {
                // No composable info in this group (internal node group)
                // Prefer userAncestor if available - user composable should "own" internal nodes
                // This makes MainContent appear instead of Column for internal layout nodes
                if (newUserAncestor != null) {
                    infoForNode = newUserAncestor;
                    usedUserAncestor = true;
                } else {
                    infoForNode = immediateAncestor;
                }
            }

            // If we have a node, map it using the determined info
            if (node != null && infoForNode != null) {
                int nodeId = System.identityHashCode(node);
                result.put(nodeId, infoForNode);
                if (groupLogCount < MAX_GROUP_LOGS) {
                    groupLogCount++;
                    String source = (thisGroupInfo != null) ? "own" : "ancestor";
                    SidekickLog.d(TAG, "Mapped node " + nodeId + " -> " + infoForNode.name + " (" + source + ")");
                }
            }

            // For children: pass current group info as immediate ancestor
            // If we used the userAncestor for this node, "consume" it (set to null for children)
            // This prevents the same user composable from appearing at every level
            ComposableInfo nextImmediate = (thisGroupInfo != null) ? thisGroupInfo : immediateAncestor;
            ComposableInfo nextUserAncestor = (usedUserAncestor && node != null) ? null : newUserAncestor;
            walkCompositionGroupsWithAncestors(group, result, nextImmediate, nextUserAncestor);

        } catch (Exception e) {
            SidekickLog.d(TAG, "Error processing composition group: " + e.getMessage());
        }
    }

    /**
     * Parses sourceInfo string to extract composable name and source location.
     * Format: "C(ComposableName)P(params)@filename.kt:lineNumber" - user composables
     * Format: "CC(ComposableName)P(params)@filename.kt" - library composables
     * Example: "C(MainContent)@MainActivity.kt" - user-defined
     * Example: "CC(Box)@Box.kt" - Compose library
     */
    private static ComposableInfo parseSourceInfo(String sourceInfo) {
        ComposableInfo info = new ComposableInfo();

        if (sourceInfo == null || sourceInfo.isEmpty()) {
            return info;
        }

        // Check for CC(...) prefix first (library composables)
        // CC means Compose Component - standard library composables
        int ccStart = sourceInfo.indexOf("CC(");
        if (ccStart >= 0) {
            int cEnd = sourceInfo.indexOf(")", ccStart);
            if (cEnd > ccStart + 3) {
                info.name = sourceInfo.substring(ccStart + 3, cEnd);
                info.isLibraryComposable = true;
            }
        } else {
            // Check for C(...) prefix (user composables or some library calls)
            int cStart = sourceInfo.indexOf("C(");
            if (cStart >= 0) {
                int cEnd = sourceInfo.indexOf(")", cStart);
                if (cEnd > cStart + 2) {
                    info.name = sourceInfo.substring(cStart + 2, cEnd);
                    // Will be updated below based on source file
                    info.isLibraryComposable = false;
                }
            }
        }

        // Extract filename - look for :filename.kt# or :filename.kt at end
        // Format can be: C(Name)P(...)line@col:FileName.kt#hash
        int ktIndex = sourceInfo.lastIndexOf(".kt");
        if (ktIndex > 0) {
            // Find the start of the filename (after last :)
            int fileStart = sourceInfo.lastIndexOf(":", ktIndex);
            if (fileStart >= 0 && fileStart < ktIndex) {
                String filename = sourceInfo.substring(fileStart + 1, ktIndex + 3);
                // Clean up - remove any leading digits/special chars
                int letterStart = 0;
                for (int i = 0; i < filename.length(); i++) {
                    if (Character.isLetter(filename.charAt(i))) {
                        letterStart = i;
                        break;
                    }
                }
                if (letterStart > 0) {
                    filename = filename.substring(letterStart);
                }
                info.fileName = filename;

                // Check if this is a known library source file
                // This overrides the CC/C prefix check for Material components
                if (LIBRARY_SOURCE_FILES.contains(filename)) {
                    info.isLibraryComposable = true;
                }
            }
        }

        return info;
    }

    /**
     * Gets all SemanticsNodes from SemanticsOwner using getAllSemanticsNodes().
     * This is the Android Studio approach for reliable semantics access.
     *
     * @param semanticsOwner The SemanticsOwner instance
     * @param mergingEnabled Whether to get merged semantics (true) or unmerged (false)
     * @return List of SemanticsNode objects
     */
    private static List<Object> getAllSemanticsNodes(Object semanticsOwner, boolean mergingEnabled) {
        List<Object> result = new ArrayList<>();

        try {
            // Try getAllSemanticsNodes(mergingEnabled: Boolean) method
            Method getAllNodes = null;
            for (Method m : semanticsOwner.getClass().getMethods()) {
                if (m.getName().contains("getAllSemanticsNodes") && m.getParameterCount() == 1) {
                    getAllNodes = m;
                    break;
                }
            }

            if (getAllNodes != null) {
                getAllNodes.setAccessible(true);
                Object nodes = getAllNodes.invoke(semanticsOwner, mergingEnabled);

                if (nodes instanceof List) {
                    result.addAll((List<?>) nodes);
                } else if (nodes instanceof Iterable) {
                    for (Object node : (Iterable<?>) nodes) {
                        result.add(node);
                    }
                }
                return result;
            }

            // Fallback: manually traverse the semantics tree
            SidekickLog.d(TAG, "getAllSemanticsNodes not found, falling back to tree traversal");
            Method getRootNode = null;
            try {
                getRootNode = semanticsOwner.getClass().getDeclaredMethod("getUnmergedRootSemanticsNode");
            } catch (NoSuchMethodException e) {
                getRootNode = semanticsOwner.getClass().getDeclaredMethod("getRootSemanticsNode");
            }
            getRootNode.setAccessible(true);
            Object rootNode = getRootNode.invoke(semanticsOwner);

            if (rootNode != null) {
                collectAllSemanticsNodesRecursive(rootNode, result);
            }

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting all semantics nodes: " + e.getMessage());
        }

        return result;
    }

    /**
     * Recursively collects all SemanticsNodes into a list.
     * Fallback for when getAllSemanticsNodes() is not available.
     */
    private static void collectAllSemanticsNodesRecursive(Object semanticsNode, List<Object> result) {
        if (semanticsNode == null) return;

        result.add(semanticsNode);

        try {
            Method getChildren = semanticsNode.getClass().getDeclaredMethod("getChildren");
            getChildren.setAccessible(true);
            Object children = getChildren.invoke(semanticsNode);

            if (children instanceof Iterable) {
                for (Object child : (Iterable<?>) children) {
                    collectAllSemanticsNodesRecursive(child, result);
                }
            }
        } catch (Exception e) {
            // No children or error
        }
    }

    /**
     * Gets the stable integer ID from a SemanticsNode.
     * This ID matches LayoutInfo.semanticsId for reliable linking.
     *
     * @param semanticsNode The SemanticsNode
     * @return The semantics ID, or -1 if not available
     */
    private static int getSemanticsNodeId(Object semanticsNode) {
        try {
            Method getId = semanticsNode.getClass().getDeclaredMethod("getId");
            getId.setAccessible(true);
            return (int) getId.invoke(semanticsNode);
        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting semantics node ID: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the semanticsId from a LayoutNode.
     * LayoutNode implements LayoutInfo which has a semanticsId property.
     * This ID links to SemanticsNode.id for reliable semantics lookup.
     *
     * @param layoutNode The LayoutNode (which implements LayoutInfo)
     * @return The semantics ID, or -1 if not available
     */
    private static int getLayoutNodeSemanticsId(Object layoutNode) {
        try {
            // LayoutNode implements LayoutInfo which has getSemanticsId()
            // Try direct method first
            for (String methodName : new String[]{"getSemanticsId", "semanticsId"}) {
                try {
                    Method method = layoutNode.getClass().getMethod(methodName);
                    method.setAccessible(true);
                    Object result = method.invoke(layoutNode);
                    if (result instanceof Integer) {
                        return (int) result;
                    }
                } catch (NoSuchMethodException e) {
                    // Try next
                }
            }

            // Try via getLayoutInfo() if LayoutNode wraps LayoutInfo
            try {
                Method getLayoutInfo = layoutNode.getClass().getDeclaredMethod("getLayoutInfo");
                getLayoutInfo.setAccessible(true);
                Object layoutInfo = getLayoutInfo.invoke(layoutNode);
                if (layoutInfo != null) {
                    Method getSemanticsId = layoutInfo.getClass().getMethod("getSemanticsId");
                    getSemanticsId.setAccessible(true);
                    return (int) getSemanticsId.invoke(layoutInfo);
                }
            } catch (NoSuchMethodException e) {
                // Not available
            }

            // Try innerCoordinator which may have semanticsId
            try {
                Method getInner = layoutNode.getClass().getDeclaredMethod("getInnerCoordinator");
                getInner.setAccessible(true);
                Object coordinator = getInner.invoke(layoutNode);
                if (coordinator != null) {
                    for (Method m : coordinator.getClass().getMethods()) {
                        if (m.getName().contains("getSemanticsId") && m.getParameterCount() == 0) {
                            m.setAccessible(true);
                            Object result = m.invoke(coordinator);
                            if (result instanceof Integer) {
                                return (int) result;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Not available via coordinator
            }

        } catch (Exception e) {
            SidekickLog.d(TAG, "Error getting layout node semantics ID: " + e.getMessage());
        }

        return -1;
    }

    // ========== DEPRECATED: Old identity-hash based approach ==========
    // Keeping for reference but no longer used.

    /**
     * @deprecated Use buildSemanticsById instead. This method uses unreliable
     * System.identityHashCode mapping that fails when object references differ.
     */
    @Deprecated
    private static void collectSemanticsWithLayoutNode(Object composeView, Map<Integer, Map<String, Object>> layoutNodeToSemantics) {
        try {
            Method getSemanticsOwner = androidComposeViewClass.getDeclaredMethod("getSemanticsOwner");
            getSemanticsOwner.setAccessible(true);
            Object semanticsOwner = getSemanticsOwner.invoke(composeView);

            if (semanticsOwner != null) {
                // Use unmerged semantics so each node has its own semantics (not bubbled up to parent)
                Method getRootNode = null;
                try {
                    getRootNode = semanticsOwner.getClass().getDeclaredMethod("getUnmergedRootSemanticsNode");
                } catch (NoSuchMethodException e) {
                    // Fallback to merged if unmerged not available
                    getRootNode = semanticsOwner.getClass().getDeclaredMethod("getRootSemanticsNode");
                }
                getRootNode.setAccessible(true);
                Object rootNode = getRootNode.invoke(semanticsOwner);

                if (rootNode != null) {
                    collectSemanticsWithLayoutNodeRecursive(rootNode, layoutNodeToSemantics);
                }
            }
        } catch (Exception e) {
            SidekickLog.e(TAG, "Error collecting semantics with layoutNode", e);
        }
    }

    /**
     * @deprecated Use buildSemanticsById instead.
     */
    @Deprecated
    private static void collectSemanticsWithLayoutNodeRecursive(Object semanticsNode, Map<Integer, Map<String, Object>> map) {
        if (semanticsNode == null) return;

        try {
            Map<String, Object> entry = new HashMap<>();

            // Get semantics ID
            Method getId = semanticsNode.getClass().getDeclaredMethod("getId");
            int semanticsId = (int) getId.invoke(semanticsNode);
            entry.put("semanticsId", semanticsId);

            // Get semantic properties
            Method getConfig = semanticsNode.getClass().getMethod("getConfig");
            Object config = getConfig.invoke(semanticsNode);
            if (config != null) {
                extractSemanticsProperties(config, entry);
            }

            // Get bounds from SemanticsNode - this gives proper screen coordinates
            try {
                Method getBoundsInRoot = semanticsNode.getClass().getDeclaredMethod("getBoundsInRoot");
                getBoundsInRoot.setAccessible(true);
                Object rect = getBoundsInRoot.invoke(semanticsNode);
                if (rect != null) {
                    int[] bounds = extractRectBounds(rect);
                    if (bounds != null) {
                        entry.put("semanticsBounds", bounds);
                    }
                }
            } catch (Exception e) {
                // Bounds not available from semantics
            }

            // Only process if we have meaningful content
            if (entry.containsKey("text") || entry.containsKey("role") ||
                entry.containsKey("contentDescription") || entry.containsKey("testTag")) {

                // Get the associated LayoutNode - try multiple method names
                Object layoutNode = null;
                for (String methodName : new String[]{"getLayoutNode", "layoutNode", "getLayoutInfo"}) {
                    try {
                        Method getLayoutNode = semanticsNode.getClass().getDeclaredMethod(methodName);
                        getLayoutNode.setAccessible(true);
                        layoutNode = getLayoutNode.invoke(semanticsNode);
                        if (layoutNode != null) break;
                    } catch (NoSuchMethodException e) {
                        // Try next method name
                    }
                }

                // If no direct method, try getting layoutInfo.layoutNode
                if (layoutNode == null) {
                    try {
                        Method getLayoutInfo = semanticsNode.getClass().getDeclaredMethod("getLayoutInfo");
                        getLayoutInfo.setAccessible(true);
                        Object layoutInfo = getLayoutInfo.invoke(semanticsNode);
                        if (layoutInfo != null) {
                            Method getNode = layoutInfo.getClass().getDeclaredMethod("getLayoutNode");
                            getNode.setAccessible(true);
                            layoutNode = getNode.invoke(layoutInfo);
                        }
                    } catch (Exception e) {
                        // Try alternative
                    }
                }

                if (layoutNode != null) {
                    // Use System.identityHashCode as key for LayoutNode identity
                    int layoutNodeId = System.identityHashCode(layoutNode);
                    map.put(layoutNodeId, entry);
                }
            }

            // Recurse children
            Method getChildren = semanticsNode.getClass().getDeclaredMethod("getChildren");
            Object children = getChildren.invoke(semanticsNode);
            if (children instanceof Iterable) {
                for (Object child : (Iterable<?>) children) {
                    collectSemanticsWithLayoutNodeRecursive(child, map);
                }
            }
        } catch (Exception e) {
            SidekickLog.e(TAG, "Error in collectSemanticsWithLayoutNodeRecursive: " + e.getMessage());
        }
    }

    /**
     * Extracts the root composable name from the first meaningful LayoutNode.
     * Skips internal Compose wrappers like Root, Box, Subcomposition.
     */
    private static String extractRootComposableName(Object layoutNode) {
        return findMeaningfulComposableName(layoutNode, 0);
    }

    private static String findMeaningfulComposableName(Object node, int depth) {
        if (node == null || depth > 10) return null;

        String className = getMeasurePolicyClassName(node);
        String name = parseComposableFromClassName(className);

        // Skip internal wrappers
        if (name != null && !isInternalWrapper(name)) {
            return name;
        }

        // Search children
        List<Object> children = getLayoutNodeChildren(node);
        for (Object child : children) {
            String childName = findMeaningfulComposableName(child, depth + 1);
            if (childName != null) {
                return childName;
            }
        }

        return null;
    }

    private static boolean isInternalWrapper(String name) {
        return name == null ||
               name.equals("Root") ||
               name.equals("Box") ||
               name.equals("Subcomposition") ||
               name.equals("Layout") ||
               name.equals("LayoutNodeSubcompositionsState");
    }

    /**
     * Captures a unified node combining layout and semantic information.
     *
     * <p>Uses the Android Studio approach for reliable semantics linking:
     * - Gets semanticsId from LayoutNode (via LayoutInfo.semanticsId)
     * - Looks up semantics by this stable integer ID (not identity hashcode)
     * - This ensures semantics are correctly matched even when object references differ</p>
     *
     * <p>Uses CompositionData for proper composable names:
     * - Gets composable name from compiler-embedded sourceInfo metadata
     * - Falls back to MeasurePolicy class name parsing if CompositionData unavailable</p>
     *
     * <p>Coordinate handling:
     * - Each node reports its OWN bounds in window coordinates (via boundsInWindow)
     * - We add the window's screen offset to get final screen coordinates
     * - NO manual accumulation of parent positions
     * - NO mixing of coordinate systems
     * - Child bounds are CLIPPED to parent bounds to reflect visual clipping</p>
     *
     * @param layoutNode The LayoutNode to capture
     * @param windowOffsetX Window's X offset on screen (for boundsInWindow conversion)
     * @param windowOffsetY Window's Y offset on screen (for boundsInWindow conversion)
     * @param depth Current depth in the tree
     * @param semanticsById Map from semanticsId (stable int) to semantics properties
     * @param composableInfoMap Map from LayoutNode identity to ComposableInfo (name, file, line)
     * @param parentBoundsToClipTo Parent bounds to clip this node's bounds to (null for root)
     * @return Map representing the unified node
     */
    private static Map<String, Object> captureUnifiedNode(Object layoutNode, int windowOffsetX, int windowOffsetY,
                                                           int depth, Map<Integer, Map<String, Object>> semanticsById,
                                                           Map<Integer, ComposableInfo> composableInfoMap,
                                                           int[] parentBoundsToClipTo) {
        if (layoutNode == null || depth > 50) {
            return null;
        }

        try {
            Map<String, Object> node = new HashMap<>();
            Class<?> nodeClass = layoutNode.getClass();

            // Compose node type marker (distinguishes from View nodes in unified tree)
            node.put("nodeType", "compose");

            // Get className from MeasurePolicy (used internally for composable detection)
            String className = getMeasurePolicyClassName(layoutNode);

            // Get node dimensions
            int width = 0, height = 0;
            try {
                Method getWidth = nodeClass.getDeclaredMethod("getWidth");
                Method getHeight = nodeClass.getDeclaredMethod("getHeight");
                getWidth.setAccessible(true);
                getHeight.setAccessible(true);
                width = (int) getWidth.invoke(layoutNode);
                height = (int) getHeight.invoke(layoutNode);
            } catch (Exception e) {
                // Dimensions not available
            }

            // Get bounds using boundsInWindow which returns a complete Rect directly from Compose runtime.
            // This is more accurate than computing position + size separately, especially for nested elements.
            int[] rectBounds = getBoundsFromCoordinates(layoutNode, windowOffsetX, windowOffsetY);
            int left, top, right, bottom;

            if (rectBounds != null) {
                left = rectBounds[0];
                top = rectBounds[1];
                right = rectBounds[2];
                bottom = rectBounds[3];
            } else {
                // Last resort: use view offset as origin + dimensions
                left = windowOffsetX;
                top = windowOffsetY;
                right = left + width;
                bottom = top + height;
            }

            // Apply clipping to parent bounds BEFORE storing and BEFORE recursing into children
            // This ensures children see the clipped bounds as their parent bounds
            if (parentBoundsToClipTo != null) {
                int clippedLeft = Math.max(left, parentBoundsToClipTo[0]);
                int clippedTop = Math.max(top, parentBoundsToClipTo[1]);
                int clippedRight = Math.min(right, parentBoundsToClipTo[2]);
                int clippedBottom = Math.min(bottom, parentBoundsToClipTo[3]);

                // Only apply clipping if result is valid
                if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
                    left = clippedLeft;
                    top = clippedTop;
                    right = clippedRight;
                    bottom = clippedBottom;
                }
            }

            Map<String, Object> boundsMap = new HashMap<>();
            boundsMap.put("left", left);
            boundsMap.put("top", top);
            boundsMap.put("right", right);
            boundsMap.put("bottom", bottom);
            node.put("bounds", boundsMap);

            // Look up semantics using stable semanticsId (Android Studio approach)
            // This is much more reliable than the old identity hashcode approach
            int semanticsId = getLayoutNodeSemanticsId(layoutNode);
            Map<String, Object> matchedSemantics = (semanticsId != -1) ? semanticsById.get(semanticsId) : null;

            // NOTE: We deliberately do NOT override layout bounds with semantics bounds
            // Semantics bounds can differ from layout bounds (e.g., touch target expansion)
            // For visual inspection, layout bounds are more accurate

            if (matchedSemantics != null) {
                // Include semantics ID for accessibility tracking
                node.put("semanticsId", semanticsId);
                if (matchedSemantics.containsKey("text")) {
                    node.put("text", matchedSemantics.get("text"));
                }
                if (matchedSemantics.containsKey("role")) {
                    node.put("role", matchedSemantics.get("role"));
                }
                if (matchedSemantics.containsKey("testTag")) {
                    node.put("testTag", matchedSemantics.get("testTag"));
                }
                if (matchedSemantics.containsKey("contentDescription")) {
                    node.put("contentDescription", matchedSemantics.get("contentDescription"));
                }
            }

            // Get composable name and source info
            // Priority 1: Use CompositionData sourceInfo (Android Studio approach - most accurate)
            int layoutNodeId = System.identityHashCode(layoutNode);
            ComposableInfo composableInfo = composableInfoMap != null ? composableInfoMap.get(layoutNodeId) : null;

            String composable = null;
            String sourceFile = null;
            int lineNumber = 0;

            if (composableInfo != null && composableInfo.name != null) {
                // Use the actual composable name from compiler metadata
                composable = composableInfo.name;
                sourceFile = composableInfo.fileName;
                lineNumber = composableInfo.lineNumber;
            }

            // Priority 2: Override with semantic role if available (Button, Checkbox, etc.)
            if (matchedSemantics != null && matchedSemantics.containsKey("role")) {
                String role = matchedSemantics.get("role").toString();
                if (role.contains("Button")) composable = "Button";
                else if (role.contains("Checkbox")) composable = "Checkbox";
                else if (role.contains("Switch")) composable = "Switch";
                else if (role.contains("Tab")) composable = "Tab";
            }

            // Priority 3: Fallback to MeasurePolicy class name parsing
            if (composable == null) {
                composable = detectComposableType(layoutNode, className);
            }

            // Normalize composable name for cleaner display (BasicText -> Text, etc.)
            String displayComposable = normalizeComposableName(composable);
            node.put("composable", displayComposable);

            // Add source file and line number if available from CompositionData
            // For library composables, add (inline) annotation; for user code, show source file without .kt
            if (sourceFile != null) {
                boolean isLibrary = LIBRARY_SOURCE_FILES.contains(sourceFile);
                if (!isLibrary) {
                    // User code - show file name without .kt extension
                    String cleanSourceFile = sourceFile.endsWith(".kt")
                        ? sourceFile.substring(0, sourceFile.length() - 3)
                        : sourceFile;
                    node.put("sourceFile", cleanSourceFile);
                } else {
                    // Library code - mark as inline (like Android Studio does)
                    node.put("inline", true);
                }
            }
            if (lineNumber > 0) {
                node.put("lineNumber", lineNumber);
            }

            // Add class name - use the final composable name (after semantic role override)
            // This ensures className matches composable for consistency
            if (displayComposable != null) {
                node.put("className", displayComposable);
            } else if (className != null) {
                // Fallback to MeasurePolicy class info when no composable detected
                ClassInfo classInfo = extractClassInfo(className);
                if (classInfo.className != null) {
                    node.put("className", classInfo.className);
                }
                if (classInfo.packageName != null) {
                    node.put("packageName", classInfo.packageName);
                }
            }

            // Extract InspectorInfo parameters and modifiers from the LayoutNode
            extractInspectorInfoParams(layoutNode, node);

            // Extract recomposition counts (if available)
            extractRecompositionCounts(layoutNode, composableInfoMap, node);

            // Generate stable ID for this node
            node.put("id", generateNodeId(layoutNode, depth));

            // Recurse children - pass current (already clipped) bounds as parent bounds for clipping
            // Apply skip/collapse logic based on Android Studio's presentation style
            int[] currentBounds = new int[] { left, top, right, bottom };
            List<Object> children = getLayoutNodeChildren(layoutNode);
            if (!children.isEmpty()) {
                List<Map<String, Object>> childNodes = new ArrayList<>();
                java.util.Set<String> childrenToCollapse = COLLAPSE_CHILDREN.get(displayComposable);

                for (Object child : children) {
                    Map<String, Object> childNode = captureUnifiedNode(child, windowOffsetX, windowOffsetY, depth + 1, semanticsById, composableInfoMap, currentBounds);
                    if (childNode != null) {
                        String childComposable = (String) childNode.get("composable");

                        // Check if this child should be skipped (promote its children)
                        if (childComposable != null && SKIP_COMPOSABLES.contains(childComposable)) {
                            // Skip this node, add its children instead
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> grandchildren = (List<Map<String, Object>>) childNode.get("children");
                            if (grandchildren != null) {
                                childNodes.addAll(grandchildren);
                            }
                        }
                        // Check if this child should be collapsed (parent dictates)
                        else if (childrenToCollapse != null && childComposable != null && childrenToCollapse.contains(childComposable)) {
                            // Collapse this child, promote its children
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> grandchildren = (List<Map<String, Object>>) childNode.get("children");
                            if (grandchildren != null) {
                                childNodes.addAll(grandchildren);
                            }
                        }
                        else {
                            childNodes.add(childNode);
                        }
                    }
                }
                if (!childNodes.isEmpty()) {
                    node.put("children", childNodes);
                }
            }

            return node;

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error capturing unified node", e);
            return null;
        }
    }

    /**
     * Captures a Compose subtree for use in the unified layout tree.
     * Called by UnifiedTreeBuilder when it encounters an AndroidComposeView in the View tree.
     *
     * @param composeView the AndroidComposeView instance
     * @return Map with "children" containing the compose node list, or null if no tree found
     */
    public static Map<String, Object> captureComposeSubtree(Object composeView) {
        ensureInitialized();

        if (composeView == null || androidComposeViewClass == null) {
            return null;
        }
        if (!androidComposeViewClass.isInstance(composeView)) {
            return null;
        }

        try {
            Object rootLayoutNode = getRootLayoutNode(composeView);
            if (rootLayoutNode == null) {
                return null;
            }

            List<Object> children = getLayoutNodeChildren(rootLayoutNode);
            if (children.isEmpty()) {
                return null;
            }

            // Get window offset for coordinate conversion
            int[] windowOffset = new int[2];
            if (composeView instanceof View) {
                View view = (View) composeView;
                int[] screenLoc = new int[2];
                int[] windowLoc = new int[2];
                view.getLocationOnScreen(screenLoc);
                view.getLocationInWindow(windowLoc);
                windowOffset[0] = screenLoc[0] - windowLoc[0];
                windowOffset[1] = screenLoc[1] - windowLoc[1];
            }

            // Build semantics and composable info
            Map<Integer, Map<String, Object>> semanticsById = buildSemanticsById(composeView);
            Map<Integer, ComposableInfo> composableInfoMap = buildComposableInfoMap(composeView);

            // Capture the tree
            Map<String, Object> rootNode = captureUnifiedNode(rootLayoutNode,
                windowOffset[0], windowOffset[1], 0, semanticsById, composableInfoMap, null);

            if (rootNode == null) {
                return null;
            }

            Map<String, Object> result = new HashMap<>();
            // The root node's children are what we want to inline
            Object rootChildren = rootNode.get("children");
            if (rootChildren != null) {
                result.put("children", rootChildren);
            } else {
                // If root has no children list, wrap it as the single child
                List<Map<String, Object>> singleChild = new ArrayList<>();
                singleChild.add(rootNode);
                result.put("children", singleChild);
            }

            return result;

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error capturing compose subtree", e);
            return null;
        }
    }

    /**
     * Extracts InspectorInfo parameters and modifiers from a LayoutNode.
     * InspectorInfo is populated when isDebugInspectorInfoEnabled = true.
     *
     * @param layoutNode the LayoutNode to inspect
     * @param node the output map to populate with parameters and modifiers
     */
    private static void extractInspectorInfoParams(Object layoutNode, Map<String, Object> node) {
        try {
            // Get modifier info list from the LayoutNode
            Method getModifierInfo = null;
            for (Method m : layoutNode.getClass().getMethods()) {
                if (m.getName().equals("getModifierInfo") && m.getParameterCount() == 0) {
                    getModifierInfo = m;
                    break;
                }
            }

            if (getModifierInfo == null) return;

            getModifierInfo.setAccessible(true);
            Object modifierInfoList = getModifierInfo.invoke(layoutNode);

            if (!(modifierInfoList instanceof List)) return;

            List<Map<String, Object>> parameters = new ArrayList<>();
            List<String> modifiers = new ArrayList<>();

            for (Object modInfo : (List<?>) modifierInfoList) {
                try {
                    // Get the modifier name
                    Method getExtra = null;
                    Method getModifier = null;
                    for (Method m : modInfo.getClass().getMethods()) {
                        if (m.getName().equals("getExtra") && m.getParameterCount() == 0) {
                            getExtra = m;
                        }
                        if (m.getName().equals("getModifier") && m.getParameterCount() == 0) {
                            getModifier = m;
                        }
                    }

                    // Extract modifier class name
                    if (getModifier != null) {
                        getModifier.setAccessible(true);
                        Object modifier = getModifier.invoke(modInfo);
                        if (modifier != null) {
                            String modName = modifier.getClass().getSimpleName();
                            if (!modName.isEmpty() && !modName.contains("$")) {
                                modifiers.add(modName);
                            }
                        }
                    }

                    // Extract InspectorInfo from "extra" field
                    if (getExtra != null) {
                        getExtra.setAccessible(true);
                        Object extra = getExtra.invoke(modInfo);
                        if (extra != null) {
                            extractInspectorInfoValues(extra, parameters);
                        }
                    }
                } catch (Exception e) {
                    // Skip this modifier
                }
            }

            if (!parameters.isEmpty()) {
                node.put("parameters", parameters);
            }
            if (!modifiers.isEmpty()) {
                node.put("modifiers", modifiers);
            }

        } catch (Exception e) {
            // InspectorInfo not available - this is normal for release builds
        }
    }

    /**
     * Extracts values from an InspectorInfo object.
     */
    private static void extractInspectorInfoValues(Object inspectorInfo, List<Map<String, Object>> params) {
        try {
            // Get name
            Method getName = null;
            Method getValue = null;
            Method getProperties = null;

            for (Method m : inspectorInfo.getClass().getMethods()) {
                String name = m.getName();
                if (name.equals("getName") && m.getParameterCount() == 0) getName = m;
                if (name.equals("getValue") && m.getParameterCount() == 0) getValue = m;
                if (name.equals("getProperties") && m.getParameterCount() == 0) getProperties = m;
            }

            // Extract properties map (parameter name → value)
            if (getProperties != null) {
                getProperties.setAccessible(true);
                Object properties = getProperties.invoke(inspectorInfo);
                if (properties instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) properties).entrySet()) {
                        Map<String, Object> param = new HashMap<>();
                        param.put("name", entry.getKey().toString());
                        Object val = entry.getValue();
                        param.put("value", val != null ? formatParamValue(val) : null);
                        param.put("type", val != null ? val.getClass().getSimpleName() : "null");
                        params.add(param);
                    }
                }
            }

            // If no properties map, use direct name/value
            if (params.isEmpty() && getName != null && getValue != null) {
                getName.setAccessible(true);
                getValue.setAccessible(true);
                Object nameObj = getName.invoke(inspectorInfo);
                Object valueObj = getValue.invoke(inspectorInfo);
                if (nameObj != null) {
                    Map<String, Object> param = new HashMap<>();
                    param.put("name", nameObj.toString());
                    param.put("value", valueObj != null ? formatParamValue(valueObj) : null);
                    param.put("type", valueObj != null ? valueObj.getClass().getSimpleName() : "null");
                    params.add(param);
                }
            }
        } catch (Exception e) {
            // InspectorInfo extraction failed
        }
    }

    /**
     * Formats a parameter value for JSON output.
     */
    private static Object formatParamValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof CharSequence) return value.toString();
        if (value instanceof Enum) return value.toString();
        // For complex objects, use simple class name + toString
        String className = value.getClass().getSimpleName();
        String str = value.toString();
        if (str.length() > 200) {
            str = str.substring(0, 200) + "...";
        }
        return str;
    }

    /**
     * Extracts recomposition counts for a Compose node (if available).
     * Looks for RecomposeScopeImpl associated with the LayoutNode.
     *
     * @param layoutNode the LayoutNode
     * @param composableInfoMap the composable info map (may contain recomposition data)
     * @param node the output map to populate
     */
    private static void extractRecompositionCounts(Object layoutNode,
                                                    Map<Integer, ComposableInfo> composableInfoMap,
                                                    Map<String, Object> node) {
        try {
            // Try to access recomposition data via the LayoutNode's owner (Composer)
            // This uses reflection to find RecomposeScope data
            Method getInnerCoordinator = null;
            for (Method m : layoutNode.getClass().getMethods()) {
                if (m.getName().equals("getInnerCoordinator") && m.getParameterCount() == 0) {
                    getInnerCoordinator = m;
                    break;
                }
            }

            if (getInnerCoordinator == null) return;

            getInnerCoordinator.setAccessible(true);
            Object coordinator = getInnerCoordinator.invoke(layoutNode);
            if (coordinator == null) return;

            // Walk to find associated RecomposeScope
            // The scope tracks invocation count
            Class<?> scopeClass = null;
            try {
                scopeClass = Class.forName("androidx.compose.runtime.RecomposeScopeImpl");
            } catch (ClassNotFoundException e) {
                return; // Compose runtime doesn't have this class
            }

            // Try to find recomposeCount via reflection on the scope
            for (Field f : scopeClass.getDeclaredFields()) {
                if (f.getName().contains("recomposeCount") || f.getName().contains("invocationCount")) {
                    f.setAccessible(true);
                    // Note: We'd need the actual scope instance, which requires deeper Compose internals
                    // For now, mark as not-yet-available
                    break;
                }
            }
        } catch (Exception e) {
            // Recomposition tracking not available - this is expected
        }
    }

    /**
     * Gets the full class name from the MeasurePolicy.
     */
    private static String getMeasurePolicyClassName(Object layoutNode) {
        try {
            Method getMeasurePolicy = layoutNode.getClass().getDeclaredMethod("getMeasurePolicy");
            getMeasurePolicy.setAccessible(true);
            Object policy = getMeasurePolicy.invoke(layoutNode);
            if (policy != null) {
                return policy.getClass().getName();
            }
        } catch (Exception e) {
            // Not available
        }
        return null;
    }

    /**
     * Detects the composable type from various sources.
     */
    private static String detectComposableType(Object layoutNode, String className) {
        // Priority 1: Check semantic role
        Map<String, Object> semantics = extractNodeSemantics(layoutNode);
        if (semantics.containsKey("role")) {
            String role = semantics.get("role").toString();
            // Map Role enum values to composable names
            if (role.contains("Button")) return "Button";
            if (role.contains("Checkbox")) return "Checkbox";
            if (role.contains("RadioButton")) return "RadioButton";
            if (role.contains("Switch")) return "Switch";
            if (role.contains("Tab")) return "Tab";
            if (role.contains("Image")) return "Image";
            if (role.contains("DropdownList")) return "DropdownMenu";
        }

        // Priority 2: Parse from className
        if (className != null) {
            String name = parseComposableFromClassName(className);
            if (name != null) {
                return name;
            }
        }

        // Priority 3: Has text but no role -> Text
        if (semantics.containsKey("text") && !semantics.containsKey("role")) {
            return "Text";
        }

        // Priority 4: Try existing getComposableName logic
        String name = getComposableName(layoutNode);
        if (name != null) {
            return name;
        }

        // Fallback
        return "Layout";
    }

    /**
     * Helper class to hold extracted class information.
     */
    private static class ClassInfo {
        String className;    // Full class name (e.g., "com.example.ui.HomeScreenKt")
        String packageName;  // Package (e.g., "com.example.ui")
        String sourceFile;   // Source file (e.g., "HomeScreen.kt")
    }

    /**
     * Extracts class information from a MeasurePolicy class name.
     * E.g., "com.example.ui.HomeScreenKt$Greeting$1" ->
     *   className: "com.example.ui.HomeScreenKt"
     *   packageName: "com.example.ui"
     *   sourceFile: "HomeScreen.kt"
     */
    private static ClassInfo extractClassInfo(String fullClassName) {
        ClassInfo info = new ClassInfo();
        if (fullClassName == null) return info;

        // Find the outer class (before first $)
        int dollarIndex = fullClassName.indexOf('$');
        String outerClass = dollarIndex > 0 ? fullClassName.substring(0, dollarIndex) : fullClassName;

        info.className = outerClass;

        // Extract package name
        int lastDot = outerClass.lastIndexOf('.');
        if (lastDot > 0) {
            info.packageName = outerClass.substring(0, lastDot);

            // Extract source file from class name
            String simpleName = outerClass.substring(lastDot + 1);
            if (simpleName.endsWith("Kt")) {
                // Kotlin file class: HomeScreenKt -> HomeScreen.kt
                info.sourceFile = simpleName.substring(0, simpleName.length() - 2) + ".kt";
            } else if (simpleName.endsWith("MeasurePolicy")) {
                // MeasurePolicy class
                info.sourceFile = simpleName.substring(0, simpleName.length() - 13) + ".kt";
            } else {
                info.sourceFile = simpleName + ".kt";
            }
        }

        return info;
    }

    /**
     * Generates a stable ID for a layout node.
     */
    private static String generateNodeId(Object layoutNode, int depth) {
        // Combine identity hash with depth for uniqueness
        int hash = System.identityHashCode(layoutNode);
        return String.format("%d_%d", depth, hash);
    }

    /**
     * Parses a composable name from a class name.
     * E.g., "androidx.compose.material3.ButtonKt$Button$1" -> "Button"
     */
    private static String parseComposableFromClassName(String className) {
        if (className == null) return null;

        // Common MeasurePolicy patterns -> clean composable names
        if (className.contains("ColumnMeasurePolicy")) {
            return "Column";
        }
        if (className.contains("RowMeasurePolicy")) {
            return "Row";
        }
        if (className.contains("BoxMeasurePolicy")) {
            return "Box";
        }
        if (className.contains("EmptyMeasurePolicy")) {
            return "Text";  // Text uses EmptyMeasurePolicy
        }
        if (className.contains("SpacerMeasurePolicy")) {
            return "Spacer";
        }
        if (className.contains("RootMeasurePolicy")) {
            return "Root";
        }
        if (className.contains("LazyListMeasurePolicy") || className.contains("LazyColumnKt")) {
            return "LazyColumn";
        }
        if (className.contains("LazyRowKt")) {
            return "LazyRow";
        }
        if (className.contains("SurfaceKt") || className.contains("Surface$")) {
            return "Surface";
        }
        if (className.contains("CardKt") || className.contains("Card$")) {
            return "Card";
        }
        if (className.contains("ScaffoldKt")) {
            return "Scaffold";
        }
        if (className.contains("IconKt") || className.contains("Icon$")) {
            return "Icon";
        }
        if (className.contains("ImageKt") || className.contains("Image$")) {
            return "Image";
        }
        if (className.contains("SubcompositionsState") || className.contains("Subcomposition")) {
            return "Subcomposition";
        }

        // Extract from Kt$ pattern: "ButtonKt$Button$1" -> "Button"
        int ktIndex = className.indexOf("Kt$");
        if (ktIndex > 0) {
            String afterKt = className.substring(ktIndex + 3);
            int dollarIndex = afterKt.indexOf("$");
            if (dollarIndex > 0) {
                return afterKt.substring(0, dollarIndex);
            }
            return afterKt;
        }

        // Extract from package: last part before $ or last part of package
        int lastDot = className.lastIndexOf(".");
        if (lastDot > 0) {
            String simpleName = className.substring(lastDot + 1);
            // Remove MeasurePolicy suffix
            if (simpleName.endsWith("MeasurePolicy")) {
                simpleName = simpleName.substring(0, simpleName.length() - 13);
                if (!simpleName.isEmpty()) {
                    return simpleName;
                }
            }
            // Remove Kt suffix and trailing numbers/lambdas
            if (simpleName.endsWith("Kt")) {
                simpleName = simpleName.substring(0, simpleName.length() - 2);
            }
            int dollar = simpleName.indexOf("$");
            if (dollar > 0) {
                simpleName = simpleName.substring(0, dollar);
            }
            if (!simpleName.isEmpty() && !simpleName.equals("Layout")) {
                return simpleName;
            }
        }

        return null;
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
            SidekickLog.d(TAG, "Compose classes found");
        } catch (ClassNotFoundException e) {
            SidekickLog.w(TAG, "Compose classes not found - app may not use Compose");
        }
    }

    /**
     * Finds all AndroidComposeView instances across ALL windows (including popups, dialogs, bottom sheets).
     *
     * <p>This searches through WindowManagerGlobal to find all root views, not just the activity's
     * decor view. This is necessary because Compose overlays like ModalBottomSheet, Dialog, and
     * Popup create separate windows that are not children of the activity's view hierarchy.</p>
     */
    private static List<Object> findComposeViews() {
        List<Object> result = new ArrayList<>();

        if (androidComposeViewClass == null) {
            return result;
        }

        try {
            // First, try to get all windows from WindowManagerGlobal
            List<View> allRootViews = getAllWindowRootViews();

            if (!allRootViews.isEmpty()) {
                SidekickLog.d(TAG, "Found " + allRootViews.size() + " window root views");
                for (int i = 0; i < allRootViews.size(); i++) {
                    View rootView = allRootViews.get(i);
                    SidekickLog.d(TAG, "  Root view [" + i + "]: " + rootView.getClass().getName() +
                          " visible=" + (rootView.getVisibility() == View.VISIBLE) +
                          " isShown=" + rootView.isShown() +
                          " size=" + rootView.getWidth() + "x" + rootView.getHeight());
                    findComposeViewsRecursive(rootView, result);
                }
            } else {
                // Fallback to activity's decor view if we can't get all windows
                SidekickLog.d(TAG, "Falling back to activity decor view");
                Activity activity = getCurrentActivity();
                if (activity != null) {
                    View decorView = activity.getWindow().getDecorView();
                    findComposeViewsRecursive(decorView, result);
                } else {
                    SidekickLog.w(TAG, "No current activity found");
                }
            }

            SidekickLog.d(TAG, "Found " + result.size() + " ComposeViews across all windows");

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error finding Compose views", e);
        }

        return result;
    }

    /**
     * Gets all root views using the best available API.
     * Uses WindowInspector (API 29+) as primary method, with WindowManagerGlobal fallback.
     * Filters to only visible, attached views and sorts by z-order (like Android Studio).
     */
    @SuppressWarnings("unchecked")
    private static List<View> getAllWindowRootViews() {
        List<View> rootViews = new ArrayList<>();

        // Try WindowInspector first (API 29+) - this is what Android Studio uses
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                rootViews = android.view.inspector.WindowInspector.getGlobalWindowViews();
                SidekickLog.d(TAG, "WindowInspector returned " + rootViews.size() + " root views");
            } catch (Exception e) {
                SidekickLog.w(TAG, "WindowInspector failed, falling back to WindowManagerGlobal: " + e.getMessage());
                rootViews = new ArrayList<>();
            }
        }

        // Fallback to WindowManagerGlobal reflection (for API < 29 or if WindowInspector fails)
        if (rootViews.isEmpty()) {
            try {
                Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
                Method getInstanceMethod = wmgClass.getDeclaredMethod("getInstance");
                Object wmgInstance = getInstanceMethod.invoke(null);

                Field viewsField = wmgClass.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object viewsObj = viewsField.get(wmgInstance);

                if (viewsObj instanceof List) {
                    List<?> views = (List<?>) viewsObj;
                    for (Object view : views) {
                        if (view instanceof View) {
                            rootViews.add((View) view);
                        }
                    }
                }
                SidekickLog.d(TAG, "WindowManagerGlobal returned " + rootViews.size() + " root views");
            } catch (Exception e) {
                SidekickLog.w(TAG, "Could not get windows from WindowManagerGlobal: " + e.getMessage());
            }
        }

        // Filter and sort like Android Studio does:
        // - Only visible and attached views
        // - Sorted by z-order (higher z = on top = processed later)
        List<View> filteredViews = new ArrayList<>();
        for (View view : rootViews) {
            if (view.getVisibility() == View.VISIBLE && view.isAttachedToWindow()) {
                filteredViews.add(view);
            }
        }

        // Sort by z-order (views with higher z are drawn on top)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            filteredViews.sort((v1, v2) -> Float.compare(v1.getZ(), v2.getZ()));
        }

        SidekickLog.d(TAG, "After filtering: " + filteredViews.size() + " visible root views");
        return filteredViews;
    }

    /**
     * Recursively finds ComposeViews in a view hierarchy.
     */
    private static void findComposeViewsRecursive(View view, List<Object> result) {
        if (androidComposeViewClass.isInstance(view)) {
            boolean isVisible = view.getVisibility() == View.VISIBLE;
            boolean isShown = view.isShown();
            boolean isAttached = view.isAttachedToWindow();
            boolean hasSize = view.getWidth() > 0 && view.getHeight() > 0;

            SidekickLog.d(TAG, "    Found ComposeView: " + view +
                  " visible=" + isVisible +
                  " isShown=" + isShown +
                  " attached=" + isAttached +
                  " size=" + view.getWidth() + "x" + view.getHeight());

            // Only include ComposeViews that are actually visible to the user
            // isShown() checks the entire view hierarchy for visibility
            if (isShown && isAttached && hasSize) {
                result.add(view);
            } else {
                SidekickLog.d(TAG, "    -> Skipping: not visible to user (isShown=" + isShown +
                      " attached=" + isAttached + " hasSize=" + hasSize + ")");
            }
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
            SidekickLog.e(TAG, "Error getting current activity", e);
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
            SidekickLog.e(TAG, "Error capturing ComposeView", e);
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
                SidekickLog.e(TAG, "Cannot get root LayoutNode", e2);
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
            SidekickLog.e(TAG, "Error capturing LayoutNode", e);
            return null;
        }
    }

    /**
     * Extracts bounds from a Rect object (left, top, right, bottom).
     */
    private static int[] extractRectBounds(Object rect) {
        if (rect == null) return null;

        try {
            Class<?> rectClass = rect.getClass();

            // Try getters first
            try {
                Method getLeft = rectClass.getMethod("getLeft");
                Method getTop = rectClass.getMethod("getTop");
                Method getRight = rectClass.getMethod("getRight");
                Method getBottom = rectClass.getMethod("getBottom");

                float left = ((Number) getLeft.invoke(rect)).floatValue();
                float top = ((Number) getTop.invoke(rect)).floatValue();
                float right = ((Number) getRight.invoke(rect)).floatValue();
                float bottom = ((Number) getBottom.invoke(rect)).floatValue();

                return new int[] {
                    Math.round(left), Math.round(top),
                    Math.round(right), Math.round(bottom)
                };
            } catch (NoSuchMethodException e) {
                // Try fields
            }

            // Try fields
            Field leftField = rectClass.getDeclaredField("left");
            Field topField = rectClass.getDeclaredField("top");
            Field rightField = rectClass.getDeclaredField("right");
            Field bottomField = rectClass.getDeclaredField("bottom");
            leftField.setAccessible(true);
            topField.setAccessible(true);
            rightField.setAccessible(true);
            bottomField.setAccessible(true);

            float left = ((Number) leftField.get(rect)).floatValue();
            float top = ((Number) topField.get(rect)).floatValue();
            float right = ((Number) rightField.get(rect)).floatValue();
            float bottom = ((Number) bottomField.get(rect)).floatValue();

            return new int[] {
                Math.round(left), Math.round(top),
                Math.round(right), Math.round(bottom)
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the bounds of a LayoutNode, trying multiple methods in order of accuracy.
     *
     * Priority order:
     * 1. boundsInWindow() - returns complete Rect directly, most accurate for nested elements
     * 2. boundsInRoot() + viewOffset - position relative to root plus ComposeView offset
     * 3. localToScreen - converts local origin to screen coords (may have issues with nested elements)
     * 4. positionInRoot + size - fallback using separate position and dimensions
     *
     * @param layoutNode The LayoutNode to get bounds for
     * @param windowOffsetX ComposeView's X offset on screen
     * @param windowOffsetY ComposeView's Y offset on screen
     * @return [left, top, right, bottom] coordinates, or null if unavailable
     */
    private static int[] getBoundsFromCoordinates(Object layoutNode, int windowOffsetX, int windowOffsetY) {
        try {
            Class<?> nodeClass = layoutNode.getClass();
            Method getCoordinates = nodeClass.getMethod("getCoordinates");
            Object coords = getCoordinates.invoke(layoutNode);

            if (coords == null) {
                return null;
            }

            // Debug: Log all available methods on coordinates
            StringBuilder coordMethods = new StringBuilder("LayoutCoordinates methods: ");
            for (Method m : coords.getClass().getMethods()) {
                if (m.getDeclaringClass() != Object.class) {
                    coordMethods.append(m.getName()).append("(").append(m.getParameterCount()).append("), ");
                }
            }
            SidekickLog.d(TAG, coordMethods.toString());

            // Get node dimensions (needed for some fallbacks)
            int width = 0, height = 0;
            try {
                Method getWidth = nodeClass.getDeclaredMethod("getWidth");
                Method getHeight = nodeClass.getDeclaredMethod("getHeight");
                getWidth.setAccessible(true);
                getHeight.setAccessible(true);
                width = (int) getWidth.invoke(layoutNode);
                height = (int) getHeight.invoke(layoutNode);
            } catch (Exception e) {
                // Continue without dimensions
            }

            // Method 1: Try boundsInWindow() - returns complete Rect directly
            // This is the most accurate method as it returns the actual bounds from Compose
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (m.getParameterCount() == 0 && methodName.contains("boundsInWindow")) {
                    SidekickLog.d(TAG, "Found boundsInWindow method: " + methodName + ", return type: " + m.getReturnType().getName());
                    m.setAccessible(true);
                    Object rect = m.invoke(coords);
                    SidekickLog.d(TAG, "boundsInWindow returned: " + (rect != null ? rect.getClass().getName() + " = " + rect : "null"));
                    if (rect != null) {
                        int[] bounds = extractRectBounds(rect);
                        SidekickLog.d(TAG, "extractRectBounds returned: " + (bounds != null ? java.util.Arrays.toString(bounds) : "null"));
                        if (bounds != null) {
                            // Add window offset to convert from window-relative to screen coordinates
                            return new int[] {
                                bounds[0] + windowOffsetX,
                                bounds[1] + windowOffsetY,
                                bounds[2] + windowOffsetX,
                                bounds[3] + windowOffsetY
                            };
                        }
                    }
                }
            }

            // Method 2: Try boundsInRoot() - returns Rect relative to root
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (m.getParameterCount() == 0 && methodName.contains("boundsInRoot")) {
                    m.setAccessible(true);
                    Object rect = m.invoke(coords);
                    if (rect != null) {
                        int[] bounds = extractRectBounds(rect);
                        if (bounds != null) {
                            // Add view offset to convert from root-relative to window coordinates
                            return new int[] {
                                bounds[0] + windowOffsetX,
                                bounds[1] + windowOffsetY,
                                bounds[2] + windowOffsetX,
                                bounds[3] + windowOffsetY
                            };
                        }
                    }
                }
            }

            // Method 3: Try localToWindow with (0,0) to get position in WINDOW coordinates
            // Window coordinates match the screenshot capture coordinate system (PixelCopy from Window)
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (methodName.contains("localToWindow") && m.getParameterCount() == 1 && m.getReturnType() == long.class) {
                    m.setAccessible(true);
                    long zeroOffset = ((long) Float.floatToIntBits(0f) << 32) | (Float.floatToIntBits(0f) & 0xFFFFFFFFL);
                    long windowPos = (long) m.invoke(coords, zeroOffset);
                    float windowX = Float.intBitsToFloat((int) (windowPos >>> 32));
                    float windowY = Float.intBitsToFloat((int) (windowPos & 0xFFFFFFFFL));
                    SidekickLog.d(TAG, "localToWindow: x=" + windowX + " y=" + windowY + " width=" + width + " height=" + height);
                    if (!Float.isNaN(windowX) && !Float.isNaN(windowY) && width > 0 && height > 0) {
                        // Add window offset to convert from window-relative to screen coordinates
                        return new int[] {
                            Math.round(windowX) + windowOffsetX, Math.round(windowY) + windowOffsetY,
                            Math.round(windowX) + windowOffsetX + width, Math.round(windowY) + windowOffsetY + height
                        };
                    }
                }
            }

            // Method 3b: Fallback to localToScreen if localToWindow not available
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (methodName.contains("localToScreen") && m.getParameterCount() == 1 && m.getReturnType() == long.class) {
                    m.setAccessible(true);
                    long zeroOffset = ((long) Float.floatToIntBits(0f) << 32) | (Float.floatToIntBits(0f) & 0xFFFFFFFFL);
                    long screenPos = (long) m.invoke(coords, zeroOffset);
                    float screenX = Float.intBitsToFloat((int) (screenPos >>> 32));
                    float screenY = Float.intBitsToFloat((int) (screenPos & 0xFFFFFFFFL));
                    SidekickLog.d(TAG, "localToScreen fallback: x=" + screenX + " y=" + screenY + " width=" + width + " height=" + height);
                    if (!Float.isNaN(screenX) && !Float.isNaN(screenY) && width > 0 && height > 0) {
                        return new int[] {
                            Math.round(screenX), Math.round(screenY),
                            Math.round(screenX) + width, Math.round(screenY) + height
                        };
                    }
                }
            }

            // Method 4: Try positionInRoot + size as last resort
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                    if (methodName.startsWith("positionInRoot") || methodName.startsWith("getPositionInRoot")) {
                        m.setAccessible(true);
                        long packed = (long) m.invoke(coords);
                        float x = Float.intBitsToFloat((int) (packed >>> 32));
                        float y = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
                        if (!Float.isNaN(x) && !Float.isNaN(y) && width > 0 && height > 0) {
                            int left = Math.round(x) + windowOffsetX;
                            int top = Math.round(y) + windowOffsetY;
                            return new int[] { left, top, left + width, top + height };
                        }
                    }
                }
            }

        } catch (Exception e) {
            SidekickLog.d(TAG, "getBoundsFromCoordinates failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Gets the bounds of a LayoutNode in window coordinates (LTRB).
     * Window coordinates are relative to the window containing the ComposeView,
     * NOT screen coordinates (which include status bar offset).
     *
     * @deprecated Use getBoundsFromCoordinates instead which has better fallback handling
     * @return [left, top, right, bottom] in window coordinates, or null if unavailable
     */
    @Deprecated
    private static int[] getBoundsInWindow(Object layoutNode) {
        try {
            Class<?> nodeClass = layoutNode.getClass();
            Method getCoordinates = nodeClass.getMethod("getCoordinates");
            Object coords = getCoordinates.invoke(layoutNode);

            if (coords == null) {
                SidekickLog.d(TAG, "getBoundsInWindow: coordinates is null");
                return null;
            }

            // Get node dimensions first
            int width = 0, height = 0;
            try {
                Method getWidth = nodeClass.getDeclaredMethod("getWidth");
                Method getHeight = nodeClass.getDeclaredMethod("getHeight");
                getWidth.setAccessible(true);
                getHeight.setAccessible(true);
                width = (int) getWidth.invoke(layoutNode);
                height = (int) getHeight.invoke(layoutNode);
            } catch (Exception e) {
                // Continue without dimensions
            }

            // Primary approach: Use localToScreen to convert (0,0) local position to screen coordinates
            // Method name is mangled: localToScreen-MK-Hz9U$jd(long) where long is packed Offset
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (methodName.contains("localToScreen") && m.getParameterCount() == 1 && m.getReturnType() == long.class) {
                    m.setAccessible(true);
                    // Pack (0f, 0f) as Offset: high 32 bits = floatToIntBits(0f), low 32 bits = floatToIntBits(0f)
                    long zeroOffset = ((long) Float.floatToIntBits(0f) << 32) | (Float.floatToIntBits(0f) & 0xFFFFFFFFL);
                    long screenPos = (long) m.invoke(coords, zeroOffset);
                    float screenX = Float.intBitsToFloat((int) (screenPos >>> 32));
                    float screenY = Float.intBitsToFloat((int) (screenPos & 0xFFFFFFFFL));
                    if (!Float.isNaN(screenX) && !Float.isNaN(screenY)) {
                        return new int[] {
                            Math.round(screenX), Math.round(screenY),
                            Math.round(screenX) + width, Math.round(screenY) + height
                        };
                    }
                }
            }

            // Fallback: Try boundsInWindow if available
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (m.getParameterCount() == 0 && methodName.contains("boundsInWindow")) {
                    m.setAccessible(true);
                    Object rect = m.invoke(coords);
                    if (rect != null) {
                        int[] bounds = extractRectBounds(rect);
                        if (bounds != null) {
                            return bounds;
                        }
                    }
                }
            }

        } catch (Exception e) {
            SidekickLog.d(TAG, "getBoundsInWindow failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Gets the position of a LayoutNode relative to the root LayoutNode.
     * This is a fallback when boundsInWindow is not available.
     *
     * @return [x, y] position relative to root, or null if unavailable
     */
    private static int[] getPositionInRoot(Object layoutNode) {
        try {
            Class<?> nodeClass = layoutNode.getClass();
            Method getCoordinates = nodeClass.getMethod("getCoordinates");
            Object coords = getCoordinates.invoke(layoutNode);

            if (coords == null) {
                return null;
            }

            // Try positionInRoot (returns Offset - packed floats as long)
            for (Method m : coords.getClass().getMethods()) {
                String methodName = m.getName();
                if (m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                    if (methodName.startsWith("positionInRoot") || methodName.startsWith("getPositionInRoot")) {
                        m.setAccessible(true);
                        long packed = (long) m.invoke(coords);
                        float x = Float.intBitsToFloat((int) (packed >>> 32));
                        float y = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
                        if (!Float.isNaN(x) && !Float.isNaN(y)) {
                            return new int[]{Math.round(x), Math.round(y)};
                        }
                    }
                }
            }
        } catch (Exception e) {
            SidekickLog.d(TAG, "getPositionInRoot failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Gets the absolute position of a LayoutNode in root coordinates.
     * Used by the legacy captureLayoutNode method.
     * Returns null if absolute position is not available.
     */
    private static int[] getAbsolutePosition(Object layoutNode) {
        // Use the new getPositionInRoot for consistency
        return getPositionInRoot(layoutNode);
    }

    /**
     * Gets the relative position of a LayoutNode within its parent.
     * Used by the legacy captureLayoutNode method as fallback.
     */
    private static int[] getRelativePosition(Object layoutNode) {
        Class<?> nodeClass = layoutNode.getClass();

        try {
            Method getCoordinates = nodeClass.getMethod("getCoordinates");
            Object coords = getCoordinates.invoke(layoutNode);

            if (coords == null) {
                return null;
            }

            // Try getPosition (local position within parent)
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
     * Gets the position of a LayoutNode (for hierarchy capture).
     * Tries absolute position first, falls back to relative.
     */
    private static int[] getNodePosition(Object layoutNode) {
        int[] absolute = getAbsolutePosition(layoutNode);
        if (absolute != null) {
            return absolute;
        }
        return getRelativePosition(layoutNode);
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
            SidekickLog.e(TAG, "Error extracting semantic properties: " + e.getMessage());
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
            SidekickLog.e(TAG, "Error capturing semantics tree", e);
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
            SidekickLog.e(TAG, "Error capturing SemanticsNode", e);
            return null;
        }
    }
}
