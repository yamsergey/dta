package io.yamsergey.dta.daemon;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAutomatorLayoutFallbackTest {

    @Test
    void prunesChromeMainUiToActionableNodes() throws Exception {
        String xml = readResource("/chrome-dump.xml");
        ObjectMapper mapper = new ObjectMapper();

        JsonNode out = UiAutomatorLayoutFallback.convertFromXml(xml, "com.android.chrome", mapper);
        assertNotNull(out, "convertFromXml returned null");

        // Top-level marker so consumers can branch on the fallback path.
        assertEquals("uiautomator", out.path("source").asString(""));
        assertEquals("layout_tree", out.path("type").asString(""));
        assertNotNull(out.get("root"));

        // Every kept node should carry nodeType and bounds.
        walkAndAssertInvariants(out.get("root"));

        // Print the pruned tree so we can eyeball it; capture sizes for the
        // run output. Also dump the raw input size for comparison.
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        System.out.println("---- PRUNED CHROME TREE ----");
        System.out.println(pretty);
        System.out.println("---- SIZES ----");
        System.out.println("Original XML : " + xml.length() + " bytes");
        System.out.println("Pruned JSON  : " + pretty.length() + " bytes");
        long compactBytes = mapper.writeValueAsBytes(out).length; // single-line, more representative of wire size
        System.out.println("Pruned JSON (compact): " + compactBytes + " bytes");
        System.out.println("Reduction (vs XML): " +
            String.format("%.1f%%", 100.0 * (1.0 - (double) compactBytes / xml.length())));
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = UiAutomatorLayoutFallbackTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void walkAndAssertInvariants(JsonNode node) {
        assertTrue(node.has("className"), "node missing className: " + node);
        assertEquals("uiautomator", node.path("nodeType").asString(""), "wrong nodeType: " + node);
        assertNotNull(node.get("bounds"), "missing bounds: " + node);
        JsonNode children = node.get("children");
        if (children != null) {
            for (JsonNode child : children) {
                walkAndAssertInvariants(child);
            }
        }
    }
}
