package io.yamsergey.dta.daemon.cdp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for Chrome DevTools Protocol (CDP) over WebSocket.
 *
 * <p>This client connects to Chrome's DevTools Protocol endpoint and provides
 * methods to interact with browser tabs, capture network traffic, and more.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Connect to Chrome DevTools
 * ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", 9222);
 *
 * // List all tabs
 * List<CdpTarget> targets = client.listTargets();
 *
 * // Find tab by URL and attach
 * CdpTarget tab = targets.stream()
 *     .filter(t -> t.urlContains("example.com"))
 *     .findFirst()
 *     .orElse(null);
 *
 * if (tab != null) {
 *     client.attachToTarget(tab);
 *     client.setNetworkEventListener(event -> {
 *         System.out.println("Network event: " + event);
 *     });
 *     client.enableNetwork();
 * }
 *
 * // Later: close
 * client.close();
 * }</pre>
 */
public class ChromeDevToolsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChromeDevToolsClient.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WS_TIMEOUT = Duration.ofSeconds(30);

    private final String host;
    private final int port;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger messageIdCounter;
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests;

    private WebSocket webSocket;
    private Consumer<CdpNetworkEvent> networkEventListener;
    private StringBuilder messageBuffer;
    private volatile boolean connected;

    /**
     * Creates a new CDP client.
     *
     * @param host the host where Chrome DevTools is listening
     * @param port the port where Chrome DevTools is listening
     */
    public ChromeDevToolsClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
        this.objectMapper = new ObjectMapper();
        this.messageIdCounter = new AtomicInteger(0);
        this.pendingRequests = new ConcurrentHashMap<>();
        this.messageBuffer = new StringBuilder();
    }

    /**
     * Lists all available debugging targets (tabs, workers, etc.).
     *
     * @return list of targets
     * @throws IOException if the request fails
     * @throws InterruptedException if interrupted
     */
    public List<CdpTarget> listTargets() throws IOException, InterruptedException {
        String url = "http://" + host + ":" + port + "/json/list";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to list targets: HTTP " + response.statusCode());
        }

        List<CdpTarget> targets = new ArrayList<>();
        JsonNode root = objectMapper.readTree(response.body());

        if (root.isArray()) {
            for (JsonNode node : root) {
                targets.add(parseTarget(node));
            }
        }

        return targets;
    }

    /**
     * Gets Chrome version information.
     *
     * @return version info as JSON
     * @throws IOException if the request fails
     * @throws InterruptedException if interrupted
     */
    public JsonNode getVersion() throws IOException, InterruptedException {
        String url = "http://" + host + ":" + port + "/json/version";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get version: HTTP " + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * Attaches to a specific target via WebSocket.
     *
     * @param target the target to attach to
     * @throws IOException if connection fails
     * @throws InterruptedException if interrupted
     */
    public void attachToTarget(CdpTarget target) throws IOException, InterruptedException {
        if (target.webSocketDebuggerUrl() == null) {
            throw new IOException("Target has no WebSocket debugger URL");
        }

        attachToWebSocket(target.webSocketDebuggerUrl());
    }

    /**
     * Attaches to a WebSocket debugger URL directly.
     *
     * @param wsUrl the WebSocket URL
     * @throws IOException if connection fails
     * @throws InterruptedException if interrupted
     */
    public void attachToWebSocket(String wsUrl) throws IOException, InterruptedException {
        if (webSocket != null) {
            webSocket.abort();
        }

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
            .connectTimeout(WS_TIMEOUT)
            .buildAsync(URI.create(wsUrl), new CdpWebSocketListener());

        try {
            webSocket = wsFuture.join();
            connected = true;
        } catch (Exception e) {
            throw new IOException("Failed to connect WebSocket: " + e.getMessage(), e);
        }
    }

    /**
     * Enables network event capture.
     * Call {@link #setNetworkEventListener} first to receive events.
     *
     * @return future that completes when Network.enable completes
     */
    public CompletableFuture<JsonNode> enableNetwork() {
        return send("Network.enable", Map.of());
    }

    /**
     * Disables network event capture.
     *
     * @return future that completes when Network.disable completes
     */
    public CompletableFuture<JsonNode> disableNetwork() {
        return send("Network.disable", Map.of());
    }

    /**
     * Gets the response body for a request.
     *
     * @param requestId the request ID from a network event
     * @return future that completes with the response body
     */
    public CompletableFuture<CdpResponseBody> getResponseBody(String requestId) {
        return send("Network.getResponseBody", Map.of("requestId", requestId))
            .thenApply(result -> {
                String body = result.path("body").asText("");
                boolean base64 = result.path("base64Encoded").asBoolean(false);
                return new CdpResponseBody(body, base64);
            });
    }

    /**
     * Enables Page domain events.
     *
     * @return future that completes when Page.enable completes
     */
    public CompletableFuture<JsonNode> enablePage() {
        return send("Page.enable", Map.of());
    }

    /**
     * Navigates to a URL.
     *
     * @param url the URL to navigate to
     * @return future that completes with navigation result
     */
    public CompletableFuture<JsonNode> navigate(String url) {
        return send("Page.navigate", Map.of("url", url));
    }

    /**
     * Sets the listener for network events.
     *
     * @param listener the event listener
     */
    public void setNetworkEventListener(Consumer<CdpNetworkEvent> listener) {
        this.networkEventListener = listener;
    }

    /**
     * Sends a CDP command and returns a future with the result.
     *
     * @param method the CDP method name
     * @param params the method parameters
     * @return future that completes with the result
     */
    public CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
        if (webSocket == null || !connected) {
            return CompletableFuture.failedFuture(
                new IOException("Not connected to WebSocket"));
        }

        int id = messageIdCounter.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("id", id);
            message.put("method", method);
            message.set("params", objectMapper.valueToTree(params));

            String json = objectMapper.writeValueAsString(message);
            webSocket.sendText(json, true);

        } catch (JacksonException e) {
            pendingRequests.remove(id);
            return CompletableFuture.failedFuture(e);
        }

        return future;
    }

    /**
     * Returns whether the client is connected to a target.
     */
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        connected = false;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        pendingRequests.clear();
    }

    private CdpTarget parseTarget(JsonNode node) {
        return new CdpTarget(
            node.path("id").asText(),
            node.path("type").asText(),
            node.path("title").asText(),
            node.path("url").asText(),
            node.path("webSocketDebuggerUrl").asText(null),
            node.path("devtoolsFrontendUrl").asText(null),
            node.path("faviconUrl").asText(null)
        );
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);

            // Check if this is a response to a request
            if (root.has("id")) {
                int id = root.get("id").asInt();
                CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                if (future != null) {
                    if (root.has("error")) {
                        future.completeExceptionally(
                            new CdpException(root.get("error")));
                    } else {
                        future.complete(root.path("result"));
                    }
                }
            }
            // Check if this is an event
            else if (root.has("method")) {
                String method = root.get("method").asText();
                JsonNode params = root.path("params");
                handleEvent(method, params);
            }

        } catch (Exception e) {
            log.error("Error handling CDP message: {}", e.getMessage(), e);
        }
    }

    private void handleEvent(String method, JsonNode params) {
        if (networkEventListener == null) {
            return;
        }

        CdpNetworkEvent event = parseNetworkEvent(method, params);
        if (event != null) {
            networkEventListener.accept(event);
        }
    }

    private CdpNetworkEvent parseNetworkEvent(String method, JsonNode params) {
        return switch (method) {
            case "Network.requestWillBeSent" -> parseRequestWillBeSent(params);
            case "Network.responseReceived" -> parseResponseReceived(params);
            case "Network.loadingFinished" -> parseLoadingFinished(params);
            case "Network.loadingFailed" -> parseLoadingFailed(params);
            case "Network.dataReceived" -> parseDataReceived(params);
            default -> null;
        };
    }

    private CdpNetworkEvent.RequestWillBeSent parseRequestWillBeSent(JsonNode params) {
        JsonNode request = params.path("request");
        JsonNode redirectResponseNode = params.path("redirectResponse");

        CdpNetworkEvent.RedirectResponse redirectResponse = null;
        if (!redirectResponseNode.isMissingNode()) {
            redirectResponse = new CdpNetworkEvent.RedirectResponse(
                redirectResponseNode.path("url").asText(),
                redirectResponseNode.path("status").asInt(),
                redirectResponseNode.path("statusText").asText(),
                parseHeaders(redirectResponseNode.path("headers"))
            );
        }

        // Extract resource type (e.g., Document, Script, Stylesheet, Image, Font, XHR, Fetch)
        JsonNode typeNode = params.path("type");
        String resourceType = typeNode.isMissingNode() ? null : typeNode.asText();
        if (resourceType != null && resourceType.isEmpty()) {
            resourceType = null;
        }

        return new CdpNetworkEvent.RequestWillBeSent(
            params.path("requestId").asText(),
            params.path("loaderId").asText(),
            params.path("documentURL").asText(),
            request.path("url").asText(),
            request.path("method").asText(),
            parseHeaders(request.path("headers")),
            request.path("postData").asText(null),
            (long) (params.path("timestamp").asDouble() * 1000),
            resourceType,
            redirectResponse
        );
    }

    private CdpNetworkEvent.ResponseReceived parseResponseReceived(JsonNode params) {
        JsonNode response = params.path("response");
        return new CdpNetworkEvent.ResponseReceived(
            params.path("requestId").asText(),
            params.path("loaderId").asText(),
            response.path("url").asText(),
            response.path("status").asInt(),
            response.path("statusText").asText(),
            parseHeaders(response.path("headers")),
            response.path("mimeType").asText(),
            (long) (params.path("timestamp").asDouble() * 1000),
            response.path("protocol").asText(),
            response.path("encodedDataLength").asLong()
        );
    }

    private CdpNetworkEvent.LoadingFinished parseLoadingFinished(JsonNode params) {
        return new CdpNetworkEvent.LoadingFinished(
            params.path("requestId").asText(),
            (long) (params.path("timestamp").asDouble() * 1000),
            params.path("encodedDataLength").asLong()
        );
    }

    private CdpNetworkEvent.LoadingFailed parseLoadingFailed(JsonNode params) {
        return new CdpNetworkEvent.LoadingFailed(
            params.path("requestId").asText(),
            (long) (params.path("timestamp").asDouble() * 1000),
            params.path("errorText").asText(),
            params.path("canceled").asBoolean(),
            params.path("blockedReason").asText(null)
        );
    }

    private CdpNetworkEvent.DataReceived parseDataReceived(JsonNode params) {
        return new CdpNetworkEvent.DataReceived(
            params.path("requestId").asText(),
            (long) (params.path("timestamp").asDouble() * 1000),
            params.path("dataLength").asLong(),
            params.path("encodedDataLength").asLong()
        );
    }

    private Map<String, String> parseHeaders(JsonNode headersNode) {
        Map<String, String> headers = new HashMap<>();
        if (headersNode.isObject()) {
            headersNode.properties().forEach(entry ->
                headers.put(entry.getKey(), entry.getValue().asText())
            );
        }
        return headers;
    }

    /**
     * WebSocket listener for CDP messages.
     */
    private class CdpWebSocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);

            if (last) {
                String message = messageBuffer.toString();
                messageBuffer = new StringBuilder();
                handleMessage(message);
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connected = false;
            // Complete all pending requests with an error
            for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
                future.completeExceptionally(
                    new IOException("WebSocket closed: " + statusCode + " " + reason));
            }
            pendingRequests.clear();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connected = false;
            // Complete all pending requests with an error
            for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
                future.completeExceptionally(error);
            }
            pendingRequests.clear();
        }
    }

    /**
     * Response body from Network.getResponseBody.
     */
    public record CdpResponseBody(String body, boolean base64Encoded) {
        /**
         * Returns the decoded body as a string.
         * If base64 encoded, decodes it first.
         */
        public String decoded() {
            if (base64Encoded) {
                return new String(java.util.Base64.getDecoder().decode(body));
            }
            return body;
        }

        /**
         * Returns the body as bytes.
         */
        public byte[] bytes() {
            if (base64Encoded) {
                return java.util.Base64.getDecoder().decode(body);
            }
            return body.getBytes();
        }
    }

    /**
     * Exception for CDP protocol errors.
     */
    public static class CdpException extends RuntimeException {
        private final int code;
        private final String errorMessage;

        public CdpException(JsonNode error) {
            super(error.path("message").asText("Unknown CDP error"));
            this.code = error.path("code").asInt(-1);
            this.errorMessage = error.path("message").asText();
        }

        public int getCode() {
            return code;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
