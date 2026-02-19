package io.yamsergey.dta.sidekick.view;

import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.ViewDebug;
import io.yamsergey.dta.sidekick.SidekickLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracts detailed ViewDebug properties from a View using reflection.
 *
 * <p>Walks the class hierarchy to find methods and fields annotated with
 * {@link ViewDebug.ExportedProperty}, invokes each to read the current value,
 * and returns categorized properties.</p>
 *
 * <p>Reflection lookups are cached per class for performance.
 * This is the on-demand endpoint — full extraction is expensive, so it's
 * kept separate from the tree walker.</p>
 *
 * <p>Reference: Android Studio's PropertyCache</p>
 */
public class ViewPropertyExtractor {

    private static final String TAG = "ViewPropertyExtractor";

    // Cache reflection lookups per class
    private static final Map<Class<?>, List<PropertyAccessor>> accessorCache = new ConcurrentHashMap<>();

    /**
     * Represents a single property accessor (method or field with @ExportedProperty).
     */
    private static class PropertyAccessor {
        final String name;
        final String category;
        final Method method;
        final Field field;
        final ViewDebug.ExportedProperty annotation;

        PropertyAccessor(String name, String category, Method method, Field field, ViewDebug.ExportedProperty annotation) {
            this.name = name;
            this.category = category;
            this.method = method;
            this.field = field;
            this.annotation = annotation;
        }

        Object getValue(Object target) throws Exception {
            if (method != null) {
                method.setAccessible(true);
                return method.invoke(target);
            } else if (field != null) {
                field.setAccessible(true);
                return field.get(target);
            }
            return null;
        }
    }

    /**
     * Extracts all ViewDebug properties from a View, organized by category.
     *
     * @param view the View to extract properties from
     * @return categorized properties: { "view": {...}, "layout": {...}, "drawing": {...}, ... }
     */
    public static Map<String, Object> extract(View view) {
        if (view == null) return null;

        Map<String, Map<String, Object>> categories = new HashMap<>();
        Resources resources = view.getResources();

        List<PropertyAccessor> accessors = getAccessors(view.getClass());

        for (PropertyAccessor accessor : accessors) {
            try {
                Object value = accessor.getValue(view);
                if (value == null) continue;

                String category = accessor.category != null && !accessor.category.isEmpty()
                    ? accessor.category : "view";

                Map<String, Object> categoryMap = categories.computeIfAbsent(category, k -> new HashMap<>());

                Map<String, Object> prop = new HashMap<>();
                prop.put("value", formatValue(value, accessor.annotation, resources));
                prop.put("type", classifyType(value, accessor.annotation));

                // If value is a resource ID, resolve it
                if (accessor.annotation != null && accessor.annotation.resolveId() && value instanceof Integer) {
                    int resId = (Integer) value;
                    String resourceName = ResourceResolver.resolveResourceName(resources, resId);
                    if (resourceName != null) {
                        prop.put("resourceName", resourceName);
                    }
                }

                categoryMap.put(accessor.name, prop);

            } catch (Exception e) {
                SidekickLog.d(TAG, "Error reading property " + accessor.name + ": " + e.getMessage());
            }
        }

        // Add basic identity info
        Map<String, Object> identity = categories.computeIfAbsent("identity", k -> new HashMap<>());
        identity.put("className", mapOf("value", view.getClass().getName(), "type", "string"));
        long drawingId = getUniqueDrawingId(view);
        identity.put("drawingId", mapOf("value", drawingId, "type", "long"));
        int viewId = view.getId();
        if (viewId != View.NO_ID) {
            identity.put("viewId", mapOf("value", viewId, "type", "int"));
            String resName = ResourceResolver.resolveResourceName(resources, viewId);
            if (resName != null) {
                identity.put("resourceId", mapOf("value", resName, "type", "string"));
            }
        }

        // Convert to result map
        Map<String, Object> result = new HashMap<>();
        result.put("className", view.getClass().getName());
        result.put("drawingId", drawingId);
        for (Map.Entry<String, Map<String, Object>> entry : categories.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    /**
     * Gets or builds the list of PropertyAccessors for a given class.
     */
    private static List<PropertyAccessor> getAccessors(Class<?> clazz) {
        List<PropertyAccessor> cached = accessorCache.get(clazz);
        if (cached != null) return cached;

        List<PropertyAccessor> accessors = new ArrayList<>();

        // Walk the class hierarchy
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            // Check methods
            for (Method method : current.getDeclaredMethods()) {
                ViewDebug.ExportedProperty annotation = method.getAnnotation(ViewDebug.ExportedProperty.class);
                if (annotation != null && method.getParameterCount() == 0) {
                    String name = method.getName();
                    // Strip "get" prefix and lowercase first char
                    if (name.startsWith("get") && name.length() > 3) {
                        name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    } else if (name.startsWith("is") && name.length() > 2) {
                        name = Character.toLowerCase(name.charAt(2)) + name.substring(3);
                    }
                    accessors.add(new PropertyAccessor(name, annotation.category(), method, null, annotation));
                }
            }

            // Check fields
            for (Field field : current.getDeclaredFields()) {
                ViewDebug.ExportedProperty annotation = field.getAnnotation(ViewDebug.ExportedProperty.class);
                if (annotation != null) {
                    accessors.add(new PropertyAccessor(field.getName(), annotation.category(), null, field, annotation));
                }
            }

            current = current.getSuperclass();
        }

        accessorCache.put(clazz, accessors);
        return accessors;
    }

    /**
     * Formats a property value for JSON output.
     */
    private static Object formatValue(Object value, ViewDebug.ExportedProperty annotation, Resources resources) {
        if (value == null) return null;

        // Handle enums via flagMapping / mapping
        if (annotation != null) {
            ViewDebug.FlagToString[] flags = annotation.flagMapping();
            if (flags.length > 0 && value instanceof Integer) {
                return formatFlags((Integer) value, flags);
            }

            ViewDebug.IntToString[] mapping = annotation.mapping();
            if (mapping.length > 0 && value instanceof Integer) {
                int intVal = (Integer) value;
                for (ViewDebug.IntToString m : mapping) {
                    if (m.from() == intVal) {
                        return m.to();
                    }
                }
            }
        }

        // Format color integers
        if (value instanceof Integer && annotation != null && annotation.formatToHexString()) {
            return ResourceResolver.colorToHex((Integer) value);
        }

        // Format primitives
        if (value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }

        // For complex objects, use toString
        return value.toString();
    }

    /**
     * Formats flag values from an integer bitmask.
     */
    private static String formatFlags(int value, ViewDebug.FlagToString[] flags) {
        StringBuilder sb = new StringBuilder();
        for (ViewDebug.FlagToString flag : flags) {
            if (flag.outputIf()) {
                if ((value & flag.mask()) == flag.equals()) {
                    if (sb.length() > 0) sb.append("|");
                    sb.append(flag.name());
                }
            } else {
                if ((value & flag.mask()) != flag.equals()) {
                    if (sb.length() > 0) sb.append("|");
                    sb.append(flag.name());
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : String.valueOf(value);
    }

    /**
     * Classifies the type of a property value.
     */
    private static String classifyType(Object value, ViewDebug.ExportedProperty annotation) {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) {
            if (annotation != null) {
                if (annotation.formatToHexString()) return "color";
                if (annotation.flagMapping().length > 0) return "flags";
                if (annotation.mapping().length > 0) return "enum";
            }
            return "int";
        }
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        if (value instanceof Double) return "double";
        if (value instanceof CharSequence) return "string";
        return "object";
    }

    /**
     * Gets the unique drawing ID (API 29+) or falls back to identity hash code.
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
     * Finds a View by its unique drawing ID within the given root view hierarchy.
     *
     * @param root the root View to search from
     * @param targetDrawingId the drawing ID to find
     * @return the matching View, or null if not found
     */
    public static View findViewByDrawingId(View root, long targetDrawingId) {
        if (root == null) return null;

        if (getUniqueDrawingId(root) == targetDrawingId) {
            return root;
        }

        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByDrawingId(group.getChildAt(i), targetDrawingId);
                if (found != null) return found;
            }
        }

        return null;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
