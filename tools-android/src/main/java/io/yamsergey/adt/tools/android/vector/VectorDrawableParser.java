package io.yamsergey.adt.tools.android.vector;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses Android Vector Drawable XML files and converts them to SVG format.
 *
 * <p>Android vector drawables use a subset of SVG path syntax. This parser reads
 * the Android XML format and generates equivalent SVG markup that can be rendered
 * by standard SVG engines like Apache Batik.</p>
 */
public class VectorDrawableParser {

    /**
     * Parses an Android vector drawable XML file and converts it to SVG format.
     *
     * @param vectorFile The Android vector drawable XML file
     * @return SVG content as a string
     * @throws Exception if parsing fails
     */
    public static String parseAndConvertToSvg(File vectorFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(vectorFile);

        Element vectorElement = doc.getDocumentElement();

        // Extract vector attributes
        String viewportWidth = getAttributeValue(vectorElement, "viewportWidth", "24");
        String viewportHeight = getAttributeValue(vectorElement, "viewportHeight", "24");
        String width = getAttributeValue(vectorElement, "width", viewportWidth + "dp");
        String height = getAttributeValue(vectorElement, "height", viewportHeight + "dp");

        // Remove 'dp' suffix if present and convert to numeric values
        width = width.replace("dp", "").replace("dip", "");
        height = height.replace("dp", "").replace("dip", "");

        // Build SVG document
        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ");
        svg.append("width=\"").append(width).append("\" ");
        svg.append("height=\"").append(height).append("\" ");
        svg.append("viewBox=\"0 0 ").append(viewportWidth).append(" ").append(viewportHeight).append("\">\n");

        // Process child elements (paths, groups, etc.)
        processVectorChildren(vectorElement, svg, new HashMap<>());

        svg.append("</svg>");

        return svg.toString();
    }

    /**
     * Recursively processes child elements of the vector drawable.
     */
    private static void processVectorChildren(Element parent, StringBuilder svg, Map<String, String> inheritedAttrs) {
        NodeList children = parent.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);

            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element element = (Element) node;
            String tagName = element.getLocalName() != null ? element.getLocalName() : element.getTagName();

            switch (tagName) {
                case "path":
                    processPath(element, svg, inheritedAttrs);
                    break;
                case "group":
                    processGroup(element, svg, inheritedAttrs);
                    break;
                case "clip-path":
                    processClipPath(element, svg);
                    break;
                default:
                    // Ignore unknown elements
                    break;
            }
        }
    }

    /**
     * Processes a path element and converts it to SVG format.
     */
    private static void processPath(Element pathElement, StringBuilder svg, Map<String, String> inheritedAttrs) {
        String pathData = getAttributeValue(pathElement, "pathData", "");
        if (pathData.isEmpty()) {
            return;
        }

        svg.append("  <path d=\"").append(pathData).append("\"");

        // Process fill color
        String fillColor = getAttributeValue(pathElement, "fillColor", inheritedAttrs.get("fillColor"));
        if (fillColor != null && !fillColor.isEmpty()) {
            fillColor = convertAndroidColor(fillColor);
            svg.append(" fill=\"").append(fillColor).append("\"");
        }

        // Process fill alpha
        String fillAlpha = getAttributeValue(pathElement, "fillAlpha", inheritedAttrs.get("fillAlpha"));
        if (fillAlpha != null && !fillAlpha.isEmpty()) {
            svg.append(" fill-opacity=\"").append(fillAlpha).append("\"");
        }

        // Process stroke color
        String strokeColor = getAttributeValue(pathElement, "strokeColor", inheritedAttrs.get("strokeColor"));
        if (strokeColor != null && !strokeColor.isEmpty()) {
            strokeColor = convertAndroidColor(strokeColor);
            svg.append(" stroke=\"").append(strokeColor).append("\"");
        }

        // Process stroke width
        String strokeWidth = getAttributeValue(pathElement, "strokeWidth", inheritedAttrs.get("strokeWidth"));
        if (strokeWidth != null && !strokeWidth.isEmpty()) {
            svg.append(" stroke-width=\"").append(strokeWidth).append("\"");
        }

        // Process stroke alpha
        String strokeAlpha = getAttributeValue(pathElement, "strokeAlpha", inheritedAttrs.get("strokeAlpha"));
        if (strokeAlpha != null && !strokeAlpha.isEmpty()) {
            svg.append(" stroke-opacity=\"").append(strokeAlpha).append("\"");
        }

        // Process stroke line cap
        String strokeLineCap = getAttributeValue(pathElement, "strokeLineCap", inheritedAttrs.get("strokeLineCap"));
        if (strokeLineCap != null && !strokeLineCap.isEmpty()) {
            svg.append(" stroke-linecap=\"").append(strokeLineCap).append("\"");
        }

        // Process stroke line join
        String strokeLineJoin = getAttributeValue(pathElement, "strokeLineJoin", inheritedAttrs.get("strokeLineJoin"));
        if (strokeLineJoin != null && !strokeLineJoin.isEmpty()) {
            svg.append(" stroke-linejoin=\"").append(strokeLineJoin).append("\"");
        }

        svg.append("/>\n");
    }

    /**
     * Processes a group element and its children.
     */
    private static void processGroup(Element groupElement, StringBuilder svg, Map<String, String> inheritedAttrs) {
        // Create a new inherited attributes map for this group
        Map<String, String> groupAttrs = new HashMap<>(inheritedAttrs);

        // Check for transformation attributes
        String translateX = getAttributeValue(groupElement, "translateX", null);
        String translateY = getAttributeValue(groupElement, "translateY", null);
        String rotation = getAttributeValue(groupElement, "rotation", null);
        String scaleX = getAttributeValue(groupElement, "scaleX", null);
        String scaleY = getAttributeValue(groupElement, "scaleY", null);
        String pivotX = getAttributeValue(groupElement, "pivotX", null);
        String pivotY = getAttributeValue(groupElement, "pivotY", null);

        boolean hasTransform = translateX != null || translateY != null || rotation != null ||
                               scaleX != null || scaleY != null;

        if (hasTransform) {
            svg.append("  <g");
            svg.append(" transform=\"");

            if (translateX != null || translateY != null) {
                String tx = translateX != null ? translateX : "0";
                String ty = translateY != null ? translateY : "0";
                svg.append("translate(").append(tx).append(",").append(ty).append(") ");
            }

            if (rotation != null && pivotX != null && pivotY != null) {
                svg.append("rotate(").append(rotation).append(",").append(pivotX).append(",").append(pivotY).append(") ");
            } else if (rotation != null) {
                svg.append("rotate(").append(rotation).append(") ");
            }

            if (scaleX != null || scaleY != null) {
                String sx = scaleX != null ? scaleX : "1";
                String sy = scaleY != null ? scaleY : sx;
                svg.append("scale(").append(sx).append(",").append(sy).append(")");
            }

            svg.append("\">\n");
        }

        // Process children
        processVectorChildren(groupElement, svg, groupAttrs);

        if (hasTransform) {
            svg.append("  </g>\n");
        }
    }

    /**
     * Processes a clip-path element.
     */
    private static void processClipPath(Element clipPathElement, StringBuilder svg) {
        String pathData = getAttributeValue(clipPathElement, "pathData", "");
        if (pathData.isEmpty()) {
            return;
        }

        // Generate a unique ID for the clip path
        String clipId = "clip_" + System.currentTimeMillis();

        svg.append("  <defs>\n");
        svg.append("    <clipPath id=\"").append(clipId).append("\">\n");
        svg.append("      <path d=\"").append(pathData).append("\"/>\n");
        svg.append("    </clipPath>\n");
        svg.append("  </defs>\n");

        // Note: The clip path would need to be applied to the appropriate elements
        // This is a simplified implementation
    }

    /**
     * Converts Android color format to SVG color format.
     * Supports formats: #RGB, #ARGB, #RRGGBB, #AARRGGBB
     */
    private static String convertAndroidColor(String androidColor) {
        if (androidColor == null || !androidColor.startsWith("#")) {
            return androidColor;
        }

        String color = androidColor.substring(1);

        // Handle #AARRGGBB format (8 digits)
        if (color.length() == 8) {
            // Extract RRGGBB, ignoring alpha (alpha is handled separately via opacity)
            return "#" + color.substring(2);
        }

        // Handle #ARGB format (4 digits)
        if (color.length() == 4) {
            // Convert to #RRGGBB
            char r = color.charAt(1);
            char g = color.charAt(2);
            char b = color.charAt(3);
            return "#" + r + r + g + g + b + b;
        }

        // Already in #RGB or #RRGGBB format
        return androidColor;
    }

    /**
     * Gets an attribute value from an element, with support for Android namespace.
     */
    private static String getAttributeValue(Element element, String attrName, String defaultValue) {
        // Try android namespace first
        String value = element.getAttributeNS("http://schemas.android.com/apk/res/android", attrName);

        if (value == null || value.isEmpty()) {
            // Try without namespace
            value = element.getAttribute(attrName);
        }

        if (value == null || value.isEmpty()) {
            // Try with android: prefix
            value = element.getAttribute("android:" + attrName);
        }

        return (value == null || value.isEmpty()) ? defaultValue : value;
    }
}
