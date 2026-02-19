package io.yamsergey.dta.sidekick.view;

import android.content.res.Resources;
import io.yamsergey.dta.sidekick.SidekickLog;

/**
 * Runtime resource ID resolution using Android's Resources API.
 *
 * <p>Converts integer resource IDs to human-readable names and values.
 * Used by ViewTreeCapture for resourceId fields and by ViewPropertyExtractor
 * for resource-typed property values.</p>
 */
public class ResourceResolver {

    private static final String TAG = "ResourceResolver";

    /**
     * Resolves a resource ID to its fully qualified name.
     *
     * @param resources the app's Resources instance
     * @param resId the resource ID to resolve
     * @return resource name like "com.example:id/button", or null if unresolvable
     */
    public static String resolveResourceName(Resources resources, int resId) {
        if (resources == null || resId == 0 || resId == -1) {
            return null;
        }
        try {
            return resources.getResourceName(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        } catch (Exception e) {
            SidekickLog.d(TAG, "Error resolving resource " + resId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves a resource ID to its entry name only (without package/type prefix).
     *
     * @param resources the app's Resources instance
     * @param resId the resource ID to resolve
     * @return entry name like "button", or null if unresolvable
     */
    public static String resolveEntryName(Resources resources, int resId) {
        if (resources == null || resId == 0 || resId == -1) {
            return null;
        }
        try {
            return resources.getResourceEntryName(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the resource type name for a given resource ID.
     *
     * @param resources the app's Resources instance
     * @param resId the resource ID
     * @return type name like "id", "color", "dimen", or null
     */
    public static String resolveTypeName(Resources resources, int resId) {
        if (resources == null || resId == 0 || resId == -1) {
            return null;
        }
        try {
            return resources.getResourceTypeName(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves a resource-typed value to a human-readable string.
     * Handles colors (→ hex), dimensions (→ dp/sp), booleans, integers, strings.
     *
     * @param resources the app's Resources instance
     * @param resId the resource ID
     * @return human-readable value, or null if unresolvable
     */
    public static String resolveResourceValue(Resources resources, int resId) {
        if (resources == null || resId == 0 || resId == -1) {
            return null;
        }
        try {
            String typeName = resources.getResourceTypeName(resId);
            if (typeName == null) return null;

            switch (typeName) {
                case "color":
                    try {
                        int color = resources.getColor(resId, null);
                        return String.format("#%08X", color);
                    } catch (Exception e) {
                        return null;
                    }
                case "dimen":
                    try {
                        float dimen = resources.getDimension(resId);
                        float density = resources.getDisplayMetrics().density;
                        return String.format("%.1fdp", dimen / density);
                    } catch (Exception e) {
                        return null;
                    }
                case "string":
                    try {
                        return resources.getString(resId);
                    } catch (Exception e) {
                        return null;
                    }
                case "bool":
                    try {
                        return String.valueOf(resources.getBoolean(resId));
                    } catch (Exception e) {
                        return null;
                    }
                case "integer":
                    try {
                        return String.valueOf(resources.getInteger(resId));
                    } catch (Exception e) {
                        return null;
                    }
                default:
                    return resolveResourceName(resources, resId);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Formats a color integer as a hex string.
     *
     * @param color the color integer (ARGB)
     * @return hex string like "#FF000000"
     */
    public static String colorToHex(int color) {
        return String.format("#%08X", color);
    }
}
