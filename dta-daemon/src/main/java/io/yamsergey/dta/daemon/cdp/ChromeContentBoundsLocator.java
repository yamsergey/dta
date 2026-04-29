package io.yamsergey.dta.daemon.cdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Resolves the screen-pixel rectangle of Chrome's visible web viewport from
 * a {@code uiautomator dump} XML. Replaces the dumpsys-window + PNG-vs-CSS
 * math we used before, which was fragile across Android versions and Chrome
 * variants and silently produced off-by-100px coordinates when any single
 * ingredient (status bar regex, screenshot dimensions, system insets) was
 * wrong.
 *
 * <p>The locator handles three foreground-Chrome shapes — verified
 * empirically against real dumps:
 * <ul>
 *   <li><b>Standalone Chrome</b> — exposes
 *       {@code class="android.webkit.WebView"} with screen-correct bounds
 *       (top edge already below the URL bar). Use bounds directly.</li>
 *   <li><b>In-app WebView</b> (host package, not Chrome) — same
 *       {@code android.webkit.WebView} class. Same handling.</li>
 *   <li><b>Chrome Custom Tab</b> — no {@code WebView} class (Chromium
 *       compositor renders directly). Chrome labels the content frame
 *       with {@code content-desc="Web View"}; the URL bar is laid out
 *       on top as a sibling FrameLayout. We use the labeled frame for
 *       width/horizontal extent, and detect the URL bar bottom by
 *       scanning leaf views in the {@code com.android.chrome} package
 *       whose top edge is in the URL-bar zone (within the first quarter
 *       of the activity content).</li>
 * </ul></p>
 *
 * <p>Pure-transform: takes XML, returns Optional. No I/O. The caller
 * (DtaOrchestrator) is responsible for sourcing the XML — typically the
 * same dump that already drove uiautomator-fallback layout building, so
 * this adds zero extra ADB calls in the common case.</p>
 */
public final class ChromeContentBoundsLocator {

    private static final Logger log = LoggerFactory.getLogger(ChromeContentBoundsLocator.class);

    private static final String CHROME_PKG = "com.android.chrome";
    private static final String WEB_VIEW_CLASS = "android.webkit.WebView";
    private static final String WEB_VIEW_DESC = "Web View";

    public record Bounds(int left, int top, int right, int bottom) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    private ChromeContentBoundsLocator() {}

    /**
     * Returns the screen rect of Chrome's visible viewport, or empty if
     * the dump doesn't contain a recognizable Chrome content surface.
     *
     * <p>Empty result means "fall back to whatever the caller had before"
     * — never throws, never logs at WARN. The locator is best-effort.</p>
     */
    public static Optional<Bounds> locate(String xmlContent) {
        if (xmlContent == null || xmlContent.isEmpty()) {
            return Optional.empty();
        }
        try {
            Document doc = parseXml(xmlContent);

            // Case A: any android.webkit.WebView. Covers standalone
            // Chrome AND in-app WebView demos. The top edge already
            // accounts for URL bars / native chrome above it, so bounds
            // are screen-correct as-is.
            Optional<Element> webView = findFirst(doc, WEB_VIEW_CLASS, null);
            if (webView.isPresent()) {
                return parseBounds(webView.get());
            }

            // Case B: CCT — labeled FrameLayout. Bounds span the activity
            // content area (from below status bar to above nav bar).
            // The URL bar is overlaid on top, so we adjust the top edge.
            Optional<Element> cctFrame = findFirstWithContentDesc(doc, WEB_VIEW_DESC, CHROME_PKG);
            if (cctFrame.isPresent()) {
                Optional<Bounds> frame = parseBounds(cctFrame.get());
                if (frame.isEmpty()) return Optional.empty();
                int urlBarBottom = findUrlBarBottom(doc, frame.get());
                return Optional.of(new Bounds(
                        frame.get().left, urlBarBottom,
                        frame.get().right, frame.get().bottom));
            }

            return Optional.empty();
        } catch (Exception e) {
            log.debug("Chrome content bounds locator failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Walks every leaf view in the {@code com.android.chrome} package
     * whose top edge is inside the URL-bar zone (the upper quarter of
     * the activity content rect) and returns the max bottom edge. The
     * URL bar's height varies — collapsed when scrolled, expanded when
     * static — so we read it live from the dump rather than assuming
     * a fixed height.
     *
     * <p>Quarter-height threshold is generous enough to catch the URL
     * bar in any state (typical: ~150-220px against 2200+px activity)
     * but tight enough to never include actual page content.</p>
     */
    private static int findUrlBarBottom(Document doc, Bounds frame) {
        int threshold = frame.top + Math.max(frame.height() / 4, 100);
        int maxBottom = frame.top;
        NodeList all = doc.getElementsByTagName("node");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            // Skip non-leaf nodes — interior URL bar containers
            // overlap the whole URL bar rect, which is fine, but we
            // also want to include them as candidates because some
            // leaves may not be exposed (CCT toolbar uses container
            // bounds with empty interior leaves on some Chrome
            // versions).
            if (!CHROME_PKG.equals(el.getAttribute("package"))) continue;
            Optional<Bounds> bo = parseBounds(el);
            if (bo.isEmpty()) continue;
            Bounds b = bo.get();
            // Must START inside URL bar zone (excludes the labeled
            // "Web View" frame itself which spans the full activity).
            if (b.top >= frame.top && b.top < threshold && b.bottom <= threshold + frame.height() / 4) {
                if (b.bottom > maxBottom) maxBottom = b.bottom;
            }
        }
        // Defensive: if we didn't find anything (no URL bar visible —
        // some CCT integrations hide it), use the activity top.
        return maxBottom == frame.top ? frame.top : maxBottom;
    }

    private static Optional<Element> findFirst(Document doc, String className, String packageName) {
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (!className.equals(el.getAttribute("class"))) continue;
            if (packageName != null && !packageName.equals(el.getAttribute("package"))) continue;
            return Optional.of(el);
        }
        return Optional.empty();
    }

    private static Optional<Element> findFirstWithContentDesc(Document doc, String desc, String packageName) {
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (!desc.equals(el.getAttribute("content-desc"))) continue;
            if (packageName != null && !packageName.equals(el.getAttribute("package"))) continue;
            return Optional.of(el);
        }
        return Optional.empty();
    }

    /**
     * Parses {@code bounds="[L,T][R,B]"} attribute values that
     * uiautomator emits. Returns empty for any malformed input —
     * never throws — so a single bad node can't kill the locator.
     */
    private static Optional<Bounds> parseBounds(Element el) {
        String s = el.getAttribute("bounds");
        if (s == null || s.isEmpty()) return Optional.empty();
        try {
            // bounds="[L,T][R,B]"
            int p1 = s.indexOf(','), p2 = s.indexOf(']'), p3 = s.indexOf('[', p2);
            int p4 = s.indexOf(',', p3), p5 = s.indexOf(']', p4);
            if (p1 < 0 || p2 < 0 || p3 < 0 || p4 < 0 || p5 < 0) return Optional.empty();
            int left = Integer.parseInt(s.substring(1, p1));
            int top = Integer.parseInt(s.substring(p1 + 1, p2));
            int right = Integer.parseInt(s.substring(p3 + 1, p4));
            int bottom = Integer.parseInt(s.substring(p4 + 1, p5));
            return Optional.of(new Bounds(left, top, right, bottom));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Defensive: uiautomator XML doesn't reference external entities
        // but production XML parsers default to "fetch DTDs over HTTP"
        // which is XXE-adjacent. Disable.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
