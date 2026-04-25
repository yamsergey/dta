package io.yamsergey.dta.daemon;

import io.yamsergey.dta.tools.android.inspect.ViewHierarchy;
import io.yamsergey.dta.tools.android.inspect.ViewHierarchyDumper;
import io.yamsergey.dta.tools.android.inspect.ViewHierarchyParser;
import io.yamsergey.dta.tools.android.inspect.ViewNode;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fallback layout tree built from {@code uiautomator dump} when sidekick
 * returns an empty tree (e.g. activity has no inspector-friendly Views or
 * Compose root). Reuses {@link ViewHierarchyDumper} + {@link ViewHierarchyParser}
 * for the I/O and XML parsing, then applies aggressive pruning so the result
 * is agent-friendly.
 *
 * <p>Pruning rules, post-order over the parsed tree:</p>
 * <ul>
 *   <li><b>Drop</b> when bounds have zero area, the resource id matches a known
 *       system bar marker, or the node is uninteresting and has no surviving
 *       children.</li>
 *   <li><b>Collapse</b> (replace with the single survivor) when the node is
 *       uninteresting and has exactly one surviving child. Eliminates the
 *       common {@code FrameLayout > LinearLayout > ...} wrapper chains.</li>
 *   <li><b>Keep stripped</b> (only {@code className} + {@code bounds} +
 *       {@code resourceId} + {@code children}) when the node is uninteresting
 *       but has multiple surviving children — preserves grouping for agents
 *       without per-attribute noise.</li>
 *   <li><b>Keep full</b> (all populated attributes) when the node is
 *       interesting (text, content-desc, hint, clickable / long-clickable /
 *       scrollable).</li>
 *   <li><b>Fuse</b> (post-pass): when a clickable parent has no label and
 *       exactly one surviving descendant that has a label but is not itself
 *       clickable, merge the descendant's label into the parent and drop the
 *       descendant. Catches the "clickable wrapper around a labeled icon"
 *       pattern (e.g. Chrome's lock-icon button).</li>
 * </ul>
 *
 * <p>Output shape mirrors the sidekick {@code /api/layout/tree} response:
 * top-level {@code screen} + {@code root}, with each node carrying
 * {@code nodeType: "uiautomator"}. A top-level {@code source: "uiautomator"}
 * marker tells consumers the fallback path produced the tree. When the
 * foreground package differs from the caller's requested one, a {@code note}
 * field is added.</p>
 */
public final class UiAutomatorLayoutFallback {

    private static final Logger log = LoggerFactory.getLogger(UiAutomatorLayoutFallback.class);

    /** Resource ids identifying system-rendered bars / decoration; never useful for agents. */
    private static final Set<String> SYSTEM_BAR_RESOURCE_IDS = Set.of(
        "android:id/statusBarBackground",
        "android:id/navigationBarBackground",
        "android:id/systemGestures"
    );

    /** Resource ids that are platform defaults — present on every screen, no filter value. */
    private static final Set<String> SKIP_RESOURCE_IDS = Set.of(
        "android:id/content"
    );

    private UiAutomatorLayoutFallback() {}

    /**
     * Runs uiautomator on the given device, parses, prunes, and returns a
     * JSON layout tree. Returns {@code null} on any failure (dump timeout,
     * parse error, empty hierarchy) so the caller can fall back to whatever
     * it had originally.
     *
     * @param device           ADB device serial (or {@code null} for default)
     * @param adbPath          path to ADB executable
     * @param requestedPackage package name the caller asked about — used only
     *                         to add a {@code note} when foreground UI is a
     *                         different package
     * @param mapper           Jackson mapper for building the result
     * @return pruned layout tree as a {@link JsonNode}, or {@code null} on failure
     */
    public static JsonNode convert(String device, String adbPath, String requestedPackage, ObjectMapper mapper) {
        Result<ViewHierarchy> dumpResult = ViewHierarchyDumper.builder()
            .deviceSerial(device)
            .adbPath(adbPath)
            .build()
            .dump();
        if (!(dumpResult instanceof Success<ViewHierarchy> dumpSuccess)) {
            log.debug("uiautomator fallback: dump failed");
            return null;
        }
        return convertFromXml(dumpSuccess.value().getXmlContent(), requestedPackage, mapper);
    }

    /**
     * Pure-transform entry point: takes a uiautomator XML string, applies the
     * pruning + fusion rules, returns the JSON tree. No I/O. Useful for tests
     * and for callers that already have the XML from another source.
     */
    public static JsonNode convertFromXml(String xmlContent, String requestedPackage, ObjectMapper mapper) {
        Result<ViewNode> parseResult = ViewHierarchyParser.parse(xmlContent);
        if (!(parseResult instanceof Success<ViewNode> parseSuccess)) {
            log.debug("uiautomator fallback: parse failed");
            return null;
        }

        ViewNode root = parseSuccess.value();
        ObjectNode pruned = pruneNode(root, mapper);
        if (pruned == null) {
            log.debug("uiautomator fallback: pruning collapsed entire tree");
            return null;
        }
        applyFusion(pruned);

        ObjectNode result = mapper.createObjectNode();
        result.put("source", "uiautomator");
        result.put("type", "layout_tree");
        result.put("timestamp", System.currentTimeMillis());

        ObjectNode screen = result.putObject("screen");
        ViewNode.Bounds rootBounds = root.getBounds();
        if (rootBounds != null) {
            screen.put("screenWidth", rootBounds.getWidth());
            screen.put("screenHeight", rootBounds.getHeight());
            screen.put("windowWidth", rootBounds.getWidth());
            screen.put("windowHeight", rootBounds.getHeight());
        }

        String foregroundPackage = root.getPackageName();
        if (foregroundPackage != null && !foregroundPackage.isEmpty()) {
            screen.put("activityPackage", foregroundPackage);
            if (requestedPackage != null
                && !requestedPackage.isEmpty()
                && !foregroundPackage.equals(requestedPackage)) {
                result.put("note",
                    "Foreground UI is " + foregroundPackage + ", not the requested "
                        + requestedPackage + ". Bring the app to the foreground for an accurate dump.");
            }
        }

        result.set("root", pruned);
        return result;
    }

    /**
     * Recursive pruning. Returns the pruned node, or {@code null} if the
     * entire subtree should be dropped.
     */
    private static ObjectNode pruneNode(ViewNode node, ObjectMapper mapper) {
        if (node == null) {
            return null;
        }

        String resourceId = node.getResourceId();
        if (resourceId != null && SYSTEM_BAR_RESOURCE_IDS.contains(resourceId)) {
            return null;
        }

        ViewNode.Bounds bounds = node.getBounds();
        if (bounds == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return null;
        }

        // Recurse first so we know what survives below.
        List<ObjectNode> survivingChildren = new ArrayList<>();
        if (node.getChildren() != null) {
            for (ViewNode child : node.getChildren()) {
                ObjectNode converted = pruneNode(child, mapper);
                if (converted != null) {
                    survivingChildren.add(converted);
                }
            }
        }

        boolean interesting = isInteresting(node);

        if (!interesting && survivingChildren.isEmpty()) {
            return null;
        }
        if (!interesting && survivingChildren.size() == 1) {
            return survivingChildren.get(0);
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("className", shortClassName(node.getClassName()));
        out.put("nodeType", "uiautomator");
        ObjectNode boundsNode = out.putObject("bounds");
        boundsNode.put("top", bounds.getTop());
        boundsNode.put("left", bounds.getLeft());
        boundsNode.put("right", bounds.getRight());
        boundsNode.put("bottom", bounds.getBottom());

        if (interesting) {
            putIfPresent(out, "text", node.getText());
            putIfPresent(out, "contentDescription", node.getContentDesc());
            putIfPresent(out, "hint", node.getHint());
            if (resourceId != null && !resourceId.isEmpty() && !SKIP_RESOURCE_IDS.contains(resourceId)) {
                out.put("resourceId", resourceId);
            }
            Map<String, Boolean> props = node.getProperties();
            if (props != null) {
                putIfTrue(out, "clickable", props.get("clickable"));
                putIfTrue(out, "longClickable", props.get("long-clickable"));
                putIfTrue(out, "scrollable", props.get("scrollable"));
                putIfTrue(out, "focusable", props.get("focusable"));
                putIfTrue(out, "selected", props.get("selected"));
                putIfTrue(out, "checked", props.get("checked"));
            }
        } else {
            // Stripped wrapper — keep resourceId only when it's an app-defined
            // identifier worth filtering on (not the platform default).
            if (resourceId != null && !resourceId.isEmpty() && !SKIP_RESOURCE_IDS.contains(resourceId)) {
                out.put("resourceId", resourceId);
            }
        }

        if (!survivingChildren.isEmpty()) {
            ArrayNode arr = out.putArray("children");
            for (ObjectNode child : survivingChildren) {
                arr.add(child);
            }
        }
        return out;
    }

    /** Interesting = something an agent could act on or read. */
    private static boolean isInteresting(ViewNode node) {
        if (notBlank(node.getText())) return true;
        if (notBlank(node.getContentDesc())) return true;
        if (notBlank(node.getHint())) return true;
        Map<String, Boolean> props = node.getProperties();
        if (props == null) return false;
        return Boolean.TRUE.equals(props.get("clickable"))
            || Boolean.TRUE.equals(props.get("long-clickable"))
            || Boolean.TRUE.equals(props.get("scrollable"));
    }

    /**
     * Post-order pass: when a clickable parent has no label and exactly one
     * surviving descendant that has a label but is not itself clickable,
     * merge the descendant's label into the parent and drop the descendant.
     */
    private static void applyFusion(ObjectNode node) {
        JsonNode childrenNode = node.get("children");
        if (!(childrenNode instanceof ArrayNode children)) {
            return;
        }
        for (JsonNode child : children) {
            if (child instanceof ObjectNode obj) {
                applyFusion(obj);
            }
        }
        if (children.size() != 1) {
            return;
        }
        if (!(children.get(0) instanceof ObjectNode child)) {
            return;
        }

        boolean parentClickable = node.path("clickable").asBoolean(false);
        if (!parentClickable) return;

        boolean parentHasLabel = notBlank(node.path("text").asString(""))
            || notBlank(node.path("contentDescription").asString(""));
        if (parentHasLabel) return;

        boolean childHasLabel = notBlank(child.path("text").asString(""))
            || notBlank(child.path("contentDescription").asString(""));
        if (!childHasLabel) return;

        boolean childClickable = child.path("clickable").asBoolean(false);
        if (childClickable) return;

        boolean childHasOwnChildren = child.has("children") && child.get("children").size() > 0;
        if (childHasOwnChildren) return;

        // Merge: take child's text/contentDesc; keep parent's existing resourceId
        // if any, otherwise inherit the child's.
        if (child.has("text")) node.set("text", child.get("text"));
        if (child.has("contentDescription")) node.set("contentDescription", child.get("contentDescription"));
        if (!node.has("resourceId") && child.has("resourceId")) {
            node.set("resourceId", child.get("resourceId"));
        }
        node.remove("children");
    }

    private static String shortClassName(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void putIfPresent(ObjectNode out, String key, String value) {
        if (notBlank(value)) {
            out.put(key, value);
        }
    }

    private static void putIfTrue(ObjectNode out, String key, Boolean value) {
        if (Boolean.TRUE.equals(value)) {
            out.put(key, true);
        }
    }
}
