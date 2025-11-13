package io.yamsergey.adt.tools.android.inspect;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.List;
import java.util.Map;

/**
 * Represents a single node in the UI view hierarchy.
 *
 * <p>This class provides a structured representation of a view element with all its
 * properties and child nodes. It's designed to be easily serializable to JSON for
 * consumption by coding agents and automation tools.</p>
 *
 * <p>Example JSON output:</p>
 * <pre>
 * {
 *   "index": 0,
 *   "className": "android.widget.Button",
 *   "packageName": "com.example.app",
 *   "text": "Click Me",
 *   "resourceId": "com.example.app:id/button",
 *   "contentDesc": "Submit button",
 *   "bounds": {
 *     "left": 100,
 *     "top": 200,
 *     "right": 300,
 *     "bottom": 250
 *   },
 *   "properties": {
 *     "clickable": true,
 *     "enabled": true,
 *     "focused": false
 *   },
 *   "children": []
 * }
 * </pre>
 */
@Getter
@Builder
public class ViewNode {

    /**
     * The index of this node among its siblings.
     */
    private final Integer index;

    /**
     * The fully qualified class name of the view (e.g., "android.widget.Button").
     */
    private final String className;

    /**
     * The package name of the application.
     */
    private final String packageName;

    /**
     * The visible text content of the view.
     */
    private final String text;

    /**
     * The resource ID of the view (e.g., "com.example.app:id/button").
     */
    private final String resourceId;

    /**
     * The content description for accessibility.
     */
    private final String contentDesc;

    /**
     * The hint text (for input fields).
     */
    private final String hint;

    /**
     * The bounding rectangle of the view.
     */
    private final Bounds bounds;

    /**
     * Boolean properties of the view (clickable, enabled, focused, etc.).
     */
    @Singular("property")
    private final Map<String, Boolean> properties;

    /**
     * Additional attributes not covered by specific fields.
     */
    @Singular("attribute")
    private final Map<String, String> attributes;

    /**
     * Child nodes of this view.
     */
    @Singular
    private final List<ViewNode> children;

    /**
     * Represents the bounding rectangle of a view.
     */
    @Getter
    @Builder
    public static class Bounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        /**
         * Returns the width of the bounds.
         */
        public int getWidth() {
            return right - left;
        }

        /**
         * Returns the height of the bounds.
         */
        public int getHeight() {
            return bottom - top;
        }

        /**
         * Returns the center X coordinate.
         */
        public int getCenterX() {
            return (left + right) / 2;
        }

        /**
         * Returns the center Y coordinate.
         */
        public int getCenterY() {
            return (top + bottom) / 2;
        }
    }
}
