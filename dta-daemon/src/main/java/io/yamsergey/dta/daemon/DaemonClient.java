package io.yamsergey.dta.daemon;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client for communicating with the DTA daemon server.
 * All methods return raw JSON strings unless otherwise noted.
 */
public class DaemonClient {

    private final String baseUrl;
    private final HttpClient httpClient;

    public DaemonClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // --- Device ---

    public String version() {
        return get("/api/version");
    }

    /**
     * Asks the daemon to stop. For standalone daemons (started via dta-cli)
     * this triggers JVM exit; for embedded daemons (plugin) it just stops the
     * Javalin server. The daemon may not have flushed its response by the
     * time the connection drops, so callers should treat any I/O exception
     * here as success-ish — verify by polling {@link #version} for socket
     * unreachability.
     */
    public String shutdownDaemon() {
        return post("/api/shutdown", null);
    }

    public String setAdbPath(String path) {
        return post("/api/config/adb?path=" + encode(path), null);
    }

    public String devices() {
        return get("/api/devices");
    }

    public String apps(String device) {
        return get("/api/apps" + deviceParam(device, true));
    }

    /**
     * Builds, installs, and launches an Android app via the daemon. The HTTP call
     * blocks for the entire build/install/launch cycle, which can take several
     * minutes — uses an extended timeout.
     *
     * <p>All parameters except {@code project} are nullable.</p>
     */
    public String runApp(String project, String device, String variant, String module, String activity) {
        StringBuilder body = new StringBuilder("{");
        body.append("\"project\":\"").append(jsonEscape(project)).append("\"");
        if (device != null)   body.append(",\"device\":\"").append(jsonEscape(device)).append("\"");
        if (variant != null)  body.append(",\"variant\":\"").append(jsonEscape(variant)).append("\"");
        if (module != null)   body.append(",\"module\":\"").append(jsonEscape(module)).append("\"");
        if (activity != null) body.append(",\"activity\":\"").append(jsonEscape(activity)).append("\"");
        body.append("}");
        return postLong("/api/run/app", body.toString());
    }

    /**
     * Triggers a debug log/state export bundle for triage. Returns the raw
     * zip bytes — caller decides where to write them. Bundle redacts host
     * package, auth headers, JWTs, and emails by default; pass {@code redact=false}
     * to opt out (typically only useful when debugging your own bundle locally).
     */
    public byte[] exportDebugLogs(String pkg, String device, boolean redact) {
        StringBuilder url = new StringBuilder("/api/debug/export-logs?package=").append(encode(pkg));
        if (device != null && !device.isEmpty()) url.append("&device=").append(encode(device));
        url.append("&redact=").append(redact);
        return postBytes(url.toString());
    }

    public byte[] screenshot(String pkg, String device) {
        return getBytes("/api/screenshot?package=" + encode(pkg) + deviceParam(device, false));
    }

    public byte[] deviceScreenshot(String device) {
        return getBytes("/api/screenshot/device" + deviceParam(device, true));
    }

    public String tap(int x, int y, String device) {
        return post("/api/tap?x=" + x + "&y=" + y + deviceParam(device, false), null);
    }

    public String longPress(int x, int y, int durationMs, String device) {
        return post("/api/long-press?x=" + x + "&y=" + y + "&duration=" + durationMs
            + deviceParam(device, false), null);
    }

    public String swipe(int x1, int y1, int x2, int y2, int duration, String device) {
        return post("/api/swipe?x1=" + x1 + "&y1=" + y1 + "&x2=" + x2 + "&y2=" + y2
            + "&duration=" + duration + deviceParam(device, false), null);
    }

    public String inputText(String text, String device) {
        return post("/api/input-text?text=" + encode(text) + deviceParam(device, false), null);
    }

    public String pressKey(String key, String device) {
        return post("/api/press-key?key=" + encode(key) + deviceParam(device, false), null);
    }

    public String scrollScreenshot(String device, String viewId, boolean scrollToTop, int maxCaptures) {
        StringBuilder url = new StringBuilder("/api/scroll-screenshot?scrollToTop=" + scrollToTop + "&maxCaptures=" + maxCaptures);
        if (device != null && !device.isEmpty()) url.append("&device=").append(encode(device));
        if (viewId != null && !viewId.isEmpty()) url.append("&viewId=").append(encode(viewId));
        return post(url.toString(), null);
    }

    // --- Layout ---

    public String layoutTree(String pkg, String device, String text, String type, String resourceId, String viewId) {
        StringBuilder url = new StringBuilder("/api/layout/tree?package=" + encode(pkg));
        if (device != null && !device.isEmpty()) url.append("&device=").append(encode(device));
        if (text != null && !text.isEmpty()) url.append("&text=").append(encode(text));
        if (type != null && !type.isEmpty()) url.append("&type=").append(encode(type));
        if (resourceId != null && !resourceId.isEmpty()) url.append("&resource_id=").append(encode(resourceId));
        if (viewId != null && !viewId.isEmpty()) url.append("&view_id=").append(encode(viewId));
        return get(url.toString());
    }

    public String layoutProperties(String pkg, String viewId, String device) {
        return get("/api/layout/properties/" + encode(viewId) + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    // --- Runtime Data ---

    public String listFiles(String pkg, String path, String device) {
        String pathParam = path != null && !path.isEmpty() ? "/" + encode(path) : "";
        return get("/api/runtime/files" + pathParam + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String navigationBackstack(String pkg, String device) {
        return get("/api/runtime/navigation/backstack?package=" + encode(pkg) + deviceParam(device, false));
    }
    public String navigationGraph(String pkg, String device) {
        return get("/api/runtime/navigation/graph?package=" + encode(pkg) + deviceParam(device, false));
    }
    public String lifecycle(String pkg, String device) {
        return get("/api/runtime/lifecycle?package=" + encode(pkg) + deviceParam(device, false));
    }
    public String memory(String pkg, String device) {
        return get("/api/runtime/memory?package=" + encode(pkg) + deviceParam(device, false));
    }
    public String threads(String pkg, String device, boolean stackTraces) {
        return get("/api/runtime/threads?package=" + encode(pkg) + deviceParam(device, false) + (stackTraces ? "&stackTraces=true" : ""));
    }
    public String viewModels(String pkg, String device) {
        return get("/api/runtime/viewmodels?package=" + encode(pkg) + deviceParam(device, false));
    }
    public String viewModelSavedState(String pkg, String viewModelId, String device) {
        return get("/api/runtime/viewmodels/" + encode(viewModelId) + "/saved-state?package="
                + encode(pkg) + deviceParam(device, false));
    }
    public String appFunctions(String pkg, String device) {
        return get("/api/runtime/app_functions?package=" + encode(pkg) + deviceParam(device, false));
    }
    public String navigate(String pkg, String device, String body) {
        return post("/api/runtime/navigate?package=" + encode(pkg) + deviceParam(device, false), body);
    }
    public String openDeepLink(String pkg, String device, String body) {
        return post("/api/runtime/open_deeplink?package=" + encode(pkg) + deviceParam(device, false), body);
    }
    public String waitFor(String pkg, String device, String body) {
        return post("/api/runtime/wait_for?package=" + encode(pkg) + deviceParam(device, false), body);
    }
    public String hiltBindings(String pkg, String device, String interfaceFilter) {
        String url = "/api/runtime/hilt_bindings?package=" + encode(pkg) + deviceParam(device, false);
        if (interfaceFilter != null && !interfaceFilter.isEmpty()) {
            url += "&interface=" + encode(interfaceFilter);
        }
        return get(url);
    }
    public String logcat(String pkg, String device, Long sinceMs, Integer maxLines, String filter, String minLevel) {
        StringBuilder url = new StringBuilder("/api/runtime/logcat?package=");
        url.append(encode(pkg)).append(deviceParam(device, false));
        if (sinceMs != null) url.append("&since=").append(sinceMs);
        if (maxLines != null) url.append("&maxLines=").append(maxLines);
        if (filter != null && !filter.isEmpty()) url.append("&filter=").append(encode(filter));
        if (minLevel != null && !minLevel.isEmpty()) url.append("&minLevel=").append(encode(minLevel));
        return get(url.toString());
    }
    public String tapAndWaitFor(String pkg, String device, int x, int y, String body) {
        return post("/api/runtime/tap_and_wait_for?package=" + encode(pkg)
            + deviceParam(device, false) + "&x=" + x + "&y=" + y, body);
    }

    public String authenticate(String pkg, String device) {
        return post("/api/runtime/authenticate?package=" + encode(pkg) + deviceParam(device, false), "");
    }

    public String setInterceptor(String pkg, String device, String script) {
        // 75s — sidekick's setInterceptor uses 60s (Rhino first-compile
        // can exceed 30s on cold emulator); we add ~15s headroom for the
        // proxy hop. Without the longer timeout, the MCP caller saw a
        // false "HTTP error" while sidekick logged successful install
        // a few seconds later.
        return post("/api/interceptor?package=" + encode(pkg) + deviceParam(device, false), script,
                java.time.Duration.ofSeconds(75));
    }

    public String clearInterceptor(String pkg, String device) {
        return delete("/api/interceptor?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String getInterceptor(String pkg, String device) {
        return get("/api/interceptor?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String getInterceptorLogs(String pkg, String device, long since) {
        return get("/api/interceptor/logs?package=" + encode(pkg) + deviceParam(device, false) + "&since=" + since);
    }

    public String getCctTraces(long since) {
        return get("/api/cct-trace?since=" + since);
    }

    public String listDatabases(String pkg, String device) {
        return get("/api/runtime/databases?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String databaseSchema(String pkg, String dbName, String device) {
        return get("/api/runtime/databases/" + encode(dbName) + "/schema?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String databaseQuery(String pkg, String dbName, String body, String device) {
        return post("/api/runtime/databases/" + encode(dbName) + "/query?package=" + encode(pkg) + deviceParam(device, false), body);
    }

    public String listSharedPrefs(String pkg, String device) {
        return get("/api/runtime/shared-prefs?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String readSharedPrefs(String pkg, String prefsName, String device) {
        return get("/api/runtime/shared-prefs/" + encode(prefsName) + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String writeSharedPrefs(String pkg, String prefsName, String body, String device) {
        return put("/api/runtime/shared-prefs/" + encode(prefsName) + "?package=" + encode(pkg) + deviceParam(device, false), body);
    }

    // --- Network ---

    public String networkRequests(String pkg, String device) {
        return networkRequests(pkg, device, null);
    }
    public String networkRequests(String pkg, String device, Long sinceMs) {
        String url = "/api/network/requests?package=" + encode(pkg) + deviceParam(device, false);
        if (sinceMs != null && sinceMs > 0) url += "&since=" + sinceMs;
        return get(url);
    }

    public String networkRequest(String pkg, String requestId, String device) {
        return get("/api/network/requests/" + encode(requestId) + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String networkRequestBody(String pkg, String requestId, String device) {
        return get("/api/network/requests/" + encode(requestId) + "/body?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String networkStats(String pkg, String device) {
        return get("/api/network/stats?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String clearNetworkRequests(String pkg, String device) {
        return delete("/api/network/clear?package=" + encode(pkg) + deviceParam(device, false));
    }

    // --- WebSocket ---

    public String websocketConnections(String pkg, String device) {
        return get("/api/websocket/connections?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String websocketConnection(String pkg, String connectionId, String device) {
        return get("/api/websocket/connections/" + encode(connectionId) + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String clearWebsocketConnections(String pkg, String device) {
        return delete("/api/websocket/clear?package=" + encode(pkg) + deviceParam(device, false));
    }

    // --- Selection: Element ---

    public String getSelectedElements(String pkg, String device) {
        return get("/api/selection/element?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String addSelectedElement(String pkg, String device, String elementJson) {
        return post("/api/selection/element?package=" + encode(pkg) + "&action=add" + deviceParam(device, false), elementJson);
    }

    public String selectElementAt(String pkg, String device, int x, int y) {
        return post("/api/selection/element-at?package=" + encode(pkg) + "&x=" + x + "&y=" + y + deviceParam(device, false), null);
    }

    public String removeSelectedElement(String pkg, String device, String elementJson) {
        return post("/api/selection/element?package=" + encode(pkg) + "&action=remove" + deviceParam(device, false), elementJson);
    }

    public String clearSelectedElements(String pkg, String device) {
        return post("/api/selection/element?package=" + encode(pkg) + "&action=clear" + deviceParam(device, false), null);
    }

    // --- Selection: Network ---

    public String getSelectedNetworkRequests(String pkg, String device) {
        return get("/api/selection/network?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String addSelectedNetworkRequest(String pkg, String device, String json) {
        return post("/api/selection/network?package=" + encode(pkg) + "&action=add" + deviceParam(device, false), json);
    }

    public String removeSelectedNetworkRequest(String pkg, String device, String json) {
        return post("/api/selection/network?package=" + encode(pkg) + "&action=remove" + deviceParam(device, false), json);
    }

    public String clearSelectedNetworkRequests(String pkg, String device) {
        return post("/api/selection/network?package=" + encode(pkg) + "&action=clear" + deviceParam(device, false), null);
    }

    // --- Selection: WebSocket Message ---

    public String getSelectedWebSocketMessages(String pkg, String device) {
        return get("/api/selection/websocket-message?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String addSelectedWebSocketMessage(String pkg, String device, String json) {
        return post("/api/selection/websocket-message?package=" + encode(pkg) + "&action=add" + deviceParam(device, false), json);
    }

    public String removeSelectedWebSocketMessage(String pkg, String device, String json) {
        return post("/api/selection/websocket-message?package=" + encode(pkg) + "&action=remove" + deviceParam(device, false), json);
    }

    public String clearSelectedWebSocketMessages(String pkg, String device) {
        return post("/api/selection/websocket-message?package=" + encode(pkg) + "&action=clear" + deviceParam(device, false), null);
    }

    // --- Mock ---

    public String mockRules(String pkg, String device) {
        return get("/api/mock/rules?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String mockRule(String pkg, String ruleId, String device) {
        return get("/api/mock/rules/" + encode(ruleId) + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String createMockRule(String pkg, String device, String ruleJson) {
        return post("/api/mock/rules?package=" + encode(pkg) + deviceParam(device, false), ruleJson);
    }

    public String updateMockRule(String pkg, String ruleId, String device, String updateJson) {
        return put("/api/mock/rules/" + encode(ruleId) + "?package=" + encode(pkg) + deviceParam(device, false), updateJson);
    }

    public String deleteMockRule(String pkg, String ruleId, String device) {
        return delete("/api/mock/rules/" + encode(ruleId) + "?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String mockConfig(String pkg, String device) {
        return get("/api/mock/config?package=" + encode(pkg) + deviceParam(device, false));
    }

    public String updateMockConfig(String pkg, String device, String configJson) {
        return put("/api/mock/config?package=" + encode(pkg) + deviceParam(device, false), configJson);
    }

    public String createMockFromRequest(String pkg, String requestId, String device) {
        return post("/api/mock/from-request/" + encode(requestId) + "?package=" + encode(pkg) + deviceParam(device, false), null);
    }

    public String createMockFromMessage(String pkg, String messageId, String device) {
        return post("/api/mock/from-message/" + encode(messageId) + "?package=" + encode(pkg) + deviceParam(device, false), null);
    }

    // --- CDP ---

    public String cdpWatchStart(String pkg, String device) {
        return post("/api/cdp/watch/start?package=" + encode(pkg) + deviceParam(device, false), null);
    }

    public String cdpWatchStop(String pkg, String device) {
        return post("/api/cdp/watch/stop?package=" + encode(pkg) + deviceParam(device, false), null);
    }

    public String cdpWatchStatus(String pkg, String device) {
        return get("/api/cdp/watch/status?package=" + encode(pkg) + deviceParam(device, false));
    }

    // --- Connection status ---

    public String connectionStatus(String pkg, String device) {
        return get("/api/connection-status?package=" + encode(pkg) + deviceParam(device, false));
    }

    // ========================================================================
    // HTTP helpers
    // ========================================================================

    private String get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return response.body();
        } catch (DaemonException e) {
            throw e;
        } catch (Exception e) {
            throw new DaemonException("HTTP GET failed: " + path, e);
        }
    }

    private byte[] getBytes(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new DaemonException("HTTP GET failed (" + response.statusCode() + "): " + new String(response.body()));
            }
            return response.body();
        } catch (DaemonException e) {
            throw e;
        } catch (Exception e) {
            throw new DaemonException("HTTP GET failed: " + path, e);
        }
    }

    private String post(String path, String body) {
        return post(path, body, Duration.ofSeconds(30));
    }

    /**
     * POST that returns the raw response bytes. Used by {@link #exportDebugLogs}
     * since the daemon streams a zip file; routing it through {@link #post}
     * would lose the binary fidelity through the String round-trip.
     */
    private byte[] postBytes(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new DaemonException("HTTP POST failed (" + response.statusCode() + "): " + new String(response.body()));
            }
            return response.body();
        } catch (DaemonException e) {
            throw e;
        } catch (Exception e) {
            throw new DaemonException("HTTP POST failed: " + path, e);
        }
    }

    /**
     * POST with an extended timeout — for long-running operations like Gradle builds
     * that can take several minutes.
     */
    private String postLong(String path, String body) {
        return post(path, body, Duration.ofMinutes(15));
    }

    private String post(String path, String body, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout);
            if (body != null) {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return response.body();
        } catch (DaemonException e) {
            throw e;
        } catch (Exception e) {
            throw new DaemonException("HTTP POST failed: " + path, e);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String put(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return response.body();
        } catch (DaemonException e) {
            throw e;
        } catch (Exception e) {
            throw new DaemonException("HTTP PUT failed: " + path, e);
        }
    }

    private String delete(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .DELETE()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return response.body();
        } catch (DaemonException e) {
            throw e;
        } catch (Exception e) {
            throw new DaemonException("HTTP DELETE failed: " + path, e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new DaemonException("HTTP error " + response.statusCode() + ": " + response.body());
        }
    }

    private String deviceParam(String device, boolean isFirst) {
        if (device == null || device.isEmpty()) return "";
        return (isFirst ? "?" : "&") + "device=" + encode(device);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
