package io.yamsergey.dta.mcp;

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

    public String devices() {
        return get("/api/devices");
    }

    public String apps(String device) {
        return get("/api/apps" + deviceParam(device, true));
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

    // --- Network ---

    public String networkRequests(String pkg, String device) {
        return get("/api/network/requests?package=" + encode(pkg) + deviceParam(device, false));
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
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
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
