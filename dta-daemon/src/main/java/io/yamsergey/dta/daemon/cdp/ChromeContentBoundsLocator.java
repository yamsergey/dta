package io.yamsergey.dta.daemon.cdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>Implementation note: regex tokenizer rather than DOM. Inside the
 * IntelliJ plugin classloader an older {@code xml-apis} jar shadows
 * {@code javax.xml.parsers}, so {@code DocumentBuilderFactory} can't be
 * obtained at runtime ({@code newDefaultInstance} missing,
 * {@code newInstance} fails on JPMS module access). uiautomator XML is
 * regular and we only need attribute lookup, so a pattern walker is
 * simpler and faster.</p>
 */
public final class ChromeContentBoundsLocator {

    private static final Logger log = LoggerFactory.getLogger(ChromeContentBoundsLocator.class);

    private static final String CHROME_PKG = "com.android.chrome";
    private static final String WEB_VIEW_CLASS = "android.webkit.WebView";
    private static final String WEB_VIEW_DESC = "Web View";

    // Matches a single <node ...> opening tag — self-closing or not.
    // We don't care about closing tags here because we only need each
    // node's attribute set independently.
    private static final Pattern NODE_OPEN = Pattern.compile("<node\\b([^>]*?)/?>");
    private static final Pattern BOUNDS_ATTR = Pattern.compile("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"");

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
            // Case A: any android.webkit.WebView. Covers standalone
            // Chrome AND in-app WebView demos. The top edge already
            // accounts for URL bars / native chrome above it, so bounds
            // are screen-correct as-is.
            Optional<String> webViewAttrs = findFirstNodeAttrs(xmlContent,
                    "class=\"" + WEB_VIEW_CLASS + "\"", null);
            if (webViewAttrs.isPresent()) {
                return parseBounds(webViewAttrs.get());
            }

            // Case B: CCT — labeled FrameLayout. Bounds span the activity
            // content area (from below status bar to above nav bar).
            // The URL bar is overlaid on top, so we adjust the top edge.
            Optional<String> cctAttrs = findFirstNodeAttrs(xmlContent,
                    "content-desc=\"" + WEB_VIEW_DESC + "\"",
                    "package=\"" + CHROME_PKG + "\"");
            if (cctAttrs.isPresent()) {
                Optional<Bounds> frame = parseBounds(cctAttrs.get());
                if (frame.isEmpty()) return Optional.empty();
                int urlBarBottom = findUrlBarBottom(xmlContent, frame.get());
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
     * Walks every {@code <node>} tag in the {@code com.android.chrome}
     * package whose top edge is inside the URL-bar zone (the upper
     * quarter of the activity content rect) and returns the max bottom
     * edge. The URL bar's height varies — collapsed when scrolled,
     * expanded when static — so we read it live from the dump rather
     * than assuming a fixed height.
     *
     * <p>Quarter-height threshold is generous enough to catch the URL
     * bar in any state (typical: ~150-220px against 2200+px activity)
     * but tight enough to never include actual page content.</p>
     */
    private static int findUrlBarBottom(String xml, Bounds frame) {
        int threshold = frame.top + Math.max(frame.height() / 4, 100);
        int upperLimit = threshold + frame.height() / 4;
        int maxBottom = frame.top;
        Matcher m = NODE_OPEN.matcher(xml);
        String pkgMarker = "package=\"" + CHROME_PKG + "\"";
        while (m.find()) {
            String attrs = m.group(1);
            if (!attrs.contains(pkgMarker)) continue;
            Optional<Bounds> bo = parseBounds(attrs);
            if (bo.isEmpty()) continue;
            Bounds b = bo.get();
            // Must START inside URL bar zone (excludes the labeled
            // "Web View" frame itself which spans the full activity).
            if (b.top >= frame.top && b.top < threshold && b.bottom <= upperLimit) {
                if (b.bottom > maxBottom) maxBottom = b.bottom;
            }
        }
        // Defensive: if we didn't find anything (no URL bar visible —
        // some CCT integrations hide it), use the activity top.
        return maxBottom == frame.top ? frame.top : maxBottom;
    }

    /**
     * Returns the attribute segment of the first {@code <node>} tag
     * matching both markers. Markers are matched as plain substrings
     * inside the attribute segment, which is safe because attribute
     * values are double-quoted and uiautomator escapes embedded quotes.
     */
    private static Optional<String> findFirstNodeAttrs(String xml, String marker1, String marker2) {
        Matcher m = NODE_OPEN.matcher(xml);
        while (m.find()) {
            String attrs = m.group(1);
            if (!attrs.contains(marker1)) continue;
            if (marker2 != null && !attrs.contains(marker2)) continue;
            return Optional.of(attrs);
        }
        return Optional.empty();
    }

    /**
     * Parses {@code bounds="[L,T][R,B]"} attribute values that
     * uiautomator emits. Returns empty for any malformed input —
     * never throws — so a single bad node can't kill the locator.
     */
    private static Optional<Bounds> parseBounds(String attrSegment) {
        Matcher m = BOUNDS_ATTR.matcher(attrSegment);
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(new Bounds(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
