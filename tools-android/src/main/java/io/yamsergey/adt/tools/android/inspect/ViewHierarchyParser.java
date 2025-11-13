package io.yamsergey.adt.tools.android.inspect;

import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses UIAutomator XML hierarchy into structured ViewNode objects.
 *
 * <p>This parser converts the XML output from UIAutomator into a tree of ViewNode objects
 * that can be easily serialized to JSON or processed programmatically.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * String xml = "..."; // UIAutomator XML output
 * Result&lt;ViewNode&gt; result = ViewHierarchyParser.parse(xml);
 * if (result instanceof Success&lt;ViewNode&gt; success) {
 *     ViewNode root = success.value();
 *     // Process the hierarchy
 * }
 * </pre>
 */
public class ViewHierarchyParser {

    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    /**
     * Parses UIAutomator XML hierarchy into a ViewNode tree.
     *
     * @param xmlContent The XML content from UIAutomator
     * @return Result containing the root ViewNode on success, or Failure with error description
     */
    public static Result<ViewNode> parse(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlContent.getBytes()));

            Element root = doc.getDocumentElement();
            if (!"hierarchy".equals(root.getNodeName())) {
                return new Failure<>(null, "Invalid hierarchy XML: root element is not 'hierarchy'");
            }

            // Find the first node element
            NodeList nodes = root.getElementsByTagName("node");
            if (nodes.getLength() == 0) {
                return new Failure<>(null, "No nodes found in hierarchy");
            }

            Element firstNode = (Element) nodes.item(0);
            ViewNode viewNode = parseNode(firstNode);

            return new Success<>(viewNode, "Hierarchy parsed successfully");

        } catch (Exception e) {
            return new Failure<>(null, "Failed to parse hierarchy XML: " + e.getMessage());
        }
    }

    /**
     * Recursively parses a node element into a ViewNode.
     */
    private static ViewNode parseNode(Element element) {
        ViewNode.ViewNodeBuilder builder = ViewNode.builder();

        // Parse basic attributes
        String index = element.getAttribute("index");
        if (!index.isEmpty()) {
            builder.index(Integer.parseInt(index));
        }

        builder.className(element.getAttribute("class"));
        builder.packageName(element.getAttribute("package"));
        builder.text(element.getAttribute("text"));
        builder.resourceId(element.getAttribute("resource-id"));
        builder.contentDesc(element.getAttribute("content-desc"));
        builder.hint(element.getAttribute("hint"));

        // Parse bounds
        String boundsStr = element.getAttribute("bounds");
        if (!boundsStr.isEmpty()) {
            ViewNode.Bounds bounds = parseBounds(boundsStr);
            if (bounds != null) {
                builder.bounds(bounds);
            }
        }

        // Parse boolean properties
        Map<String, Boolean> properties = new HashMap<>();
        addBooleanProperty(properties, element, "checkable");
        addBooleanProperty(properties, element, "checked");
        addBooleanProperty(properties, element, "clickable");
        addBooleanProperty(properties, element, "enabled");
        addBooleanProperty(properties, element, "focusable");
        addBooleanProperty(properties, element, "focused");
        addBooleanProperty(properties, element, "scrollable");
        addBooleanProperty(properties, element, "long-clickable");
        addBooleanProperty(properties, element, "password");
        addBooleanProperty(properties, element, "selected");

        properties.forEach(builder::property);

        // Parse additional attributes
        Map<String, String> attributes = new HashMap<>();
        String drawingOrder = element.getAttribute("drawing-order");
        if (!drawingOrder.isEmpty()) {
            attributes.put("drawing-order", drawingOrder);
        }
        attributes.forEach(builder::attribute);

        // Parse child nodes
        NodeList children = element.getChildNodes();
        List<ViewNode> childNodes = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "node".equals(child.getNodeName())) {
                childNodes.add(parseNode((Element) child));
            }
        }
        childNodes.forEach(builder::child);

        return builder.build();
    }

    /**
     * Parses bounds string like "[100,200][300,400]" into a Bounds object.
     */
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

    /**
     * Adds a boolean property from the element attributes.
     */
    private static void addBooleanProperty(Map<String, Boolean> properties, Element element, String attrName) {
        String value = element.getAttribute(attrName);
        if (!value.isEmpty()) {
            properties.put(attrName, Boolean.parseBoolean(value));
        }
    }
}
