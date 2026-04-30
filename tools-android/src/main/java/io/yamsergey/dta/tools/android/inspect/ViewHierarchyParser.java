package io.yamsergey.dta.tools.android.inspect;

import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses UIAutomator XML hierarchy into structured ViewNode objects.
 *
 * <p>Hand-rolled tag tokenizer rather than {@link javax.xml.parsers.DocumentBuilderFactory}.
 * Inside the IntelliJ plugin classloader the JDK's JAXP factory lookup
 * fails: an older {@code xml-apis} jar shadows {@code javax.xml.parsers}
 * (causing {@code NoSuchMethodError} on {@code newDefaultInstance}), and
 * the standard {@code newInstance()} path hits a JPMS module-access error
 * because the unnamed plugin module can't reach
 * {@code com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl}.
 * UIAutomator XML is regular enough that a 60-line parser is the right
 * trade — no dependency, no classloader magic.</p>
 */
public class ViewHierarchyParser {

    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    // Matches one tag at a time: <tag ...>, <tag .../>, or </tag>. Tag
    // bodies may contain double-quoted attribute values; the value can
    // include any character except an unescaped double quote (uiautomator
    // escapes embedded quotes as &quot;).
    private static final Pattern TAG_PATTERN = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9_-]*)((?:\\s+[A-Za-z][A-Za-z0-9_-]*=\"[^\"]*\")*)\\s*(/?)>");
    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Za-z][A-Za-z0-9_-]*)=\"([^\"]*)\"");

    public static Result<ViewNode> parse(String xmlContent) {
        try {
            Matcher m = TAG_PATTERN.matcher(xmlContent);
            Deque<ViewNode.ViewNodeBuilder> stack = new ArrayDeque<>();
            // Children are accumulated against the open builder via .child(); we
            // don't need a parallel children stack.
            ViewNode firstNode = null;
            boolean sawHierarchy = false;

            while (m.find()) {
                boolean isClosing = !m.group(1).isEmpty();
                String name = m.group(2);
                String attrs = m.group(3);
                boolean selfClosing = !m.group(4).isEmpty();

                if ("hierarchy".equals(name)) {
                    sawHierarchy = !isClosing;
                    continue;
                }
                if (!"node".equals(name)) continue;

                if (isClosing) {
                    if (stack.isEmpty()) {
                        return new Failure<>(null, "Malformed hierarchy XML: unmatched </node>");
                    }
                    ViewNode finished = stack.pop().build();
                    if (stack.isEmpty()) {
                        if (firstNode == null) firstNode = finished;
                    } else {
                        stack.peek().child(finished);
                    }
                    continue;
                }

                ViewNode.ViewNodeBuilder b = ViewNode.builder();
                applyAttributes(b, attrs);

                if (selfClosing) {
                    ViewNode finished = b.build();
                    if (stack.isEmpty()) {
                        if (firstNode == null) firstNode = finished;
                    } else {
                        stack.peek().child(finished);
                    }
                } else {
                    stack.push(b);
                }
            }

            if (!sawHierarchy) {
                return new Failure<>(null, "Invalid hierarchy XML: <hierarchy> root not found");
            }
            if (firstNode == null) {
                return new Failure<>(null, "No nodes found in hierarchy");
            }
            if (!stack.isEmpty()) {
                return new Failure<>(null, "Malformed hierarchy XML: unclosed <node>");
            }
            return new Success<>(firstNode, "Hierarchy parsed successfully");
        } catch (Exception e) {
            return new Failure<>(null, "Failed to parse hierarchy XML: " + e.getMessage());
        }
    }

    private static void applyAttributes(ViewNode.ViewNodeBuilder builder, String attrSegment) {
        Map<String, String> attrs = new HashMap<>();
        Matcher am = ATTR_PATTERN.matcher(attrSegment);
        while (am.find()) {
            attrs.put(am.group(1), unescapeXml(am.group(2)));
        }

        String index = attrs.get("index");
        if (index != null && !index.isEmpty()) {
            try { builder.index(Integer.parseInt(index)); } catch (NumberFormatException ignored) {}
        }

        builder.className(attrs.getOrDefault("class", ""));
        builder.packageName(attrs.getOrDefault("package", ""));
        builder.text(attrs.getOrDefault("text", ""));
        builder.resourceId(attrs.getOrDefault("resource-id", ""));
        builder.contentDesc(attrs.getOrDefault("content-desc", ""));
        builder.hint(attrs.getOrDefault("hint", ""));

        String boundsStr = attrs.get("bounds");
        if (boundsStr != null && !boundsStr.isEmpty()) {
            ViewNode.Bounds bounds = parseBounds(boundsStr);
            if (bounds != null) builder.bounds(bounds);
        }

        Map<String, Boolean> properties = new HashMap<>();
        addBooleanProperty(properties, attrs, "checkable");
        addBooleanProperty(properties, attrs, "checked");
        addBooleanProperty(properties, attrs, "clickable");
        addBooleanProperty(properties, attrs, "enabled");
        addBooleanProperty(properties, attrs, "focusable");
        addBooleanProperty(properties, attrs, "focused");
        addBooleanProperty(properties, attrs, "scrollable");
        addBooleanProperty(properties, attrs, "long-clickable");
        addBooleanProperty(properties, attrs, "password");
        addBooleanProperty(properties, attrs, "selected");
        properties.forEach(builder::property);

        String drawingOrder = attrs.get("drawing-order");
        if (drawingOrder != null && !drawingOrder.isEmpty()) {
            builder.attribute("drawing-order", drawingOrder);
        }
    }

    private static ViewNode.Bounds parseBounds(String boundsStr) {
        Matcher matcher = BOUNDS_PATTERN.matcher(boundsStr);
        if (matcher.matches()) {
            return ViewNode.Bounds.builder()
                    .left(Integer.parseInt(matcher.group(1)))
                    .top(Integer.parseInt(matcher.group(2)))
                    .right(Integer.parseInt(matcher.group(3)))
                    .bottom(Integer.parseInt(matcher.group(4)))
                    .build();
        }
        return null;
    }

    private static void addBooleanProperty(Map<String, Boolean> properties, Map<String, String> attrs, String attrName) {
        String value = attrs.get(attrName);
        if (value != null && !value.isEmpty()) {
            properties.put(attrName, Boolean.parseBoolean(value));
        }
    }

    private static String unescapeXml(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
