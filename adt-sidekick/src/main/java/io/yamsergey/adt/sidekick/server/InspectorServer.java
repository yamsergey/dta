package io.yamsergey.adt.sidekick.server;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.yamsergey.adt.sidekick.compose.ComposeInspector;
import io.yamsergey.adt.sidekick.compose.ComposeHitTester;
import io.yamsergey.adt.sidekick.network.HttpHeader;
import io.yamsergey.adt.sidekick.network.HttpRequest;
import io.yamsergey.adt.sidekick.network.HttpResponse;
import io.yamsergey.adt.sidekick.network.HttpTransaction;
import io.yamsergey.adt.sidekick.network.NetworkInspector;

/**
 * Simple HTTP server for ADT Sidekick inspection endpoints.
 *
 * <p>Provides REST-like endpoints for inspecting various Android components.
 * Runs on localhost only for security.</p>
 */
public class InspectorServer {

    private static final String TAG = "InspectorServer";
    private static volatile InspectorServer instance;

    private final Gson gson;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // SSE streaming clients
    private final Set<OutputStream> sseClients = ConcurrentHashMap.newKeySet();
    private final NetworkInspector.TransactionListener transactionListener;

    private ServerSocket serverSocket;
    private int port;

    private InspectorServer() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Create listener for broadcasting events to SSE clients
        // Both events are sent; the server merges them by transaction ID
        this.transactionListener = new NetworkInspector.TransactionListener() {
            @Override
            public void onTransactionStarted(HttpTransaction transaction) {
                broadcastEvent("transaction_started", transaction);
            }

            @Override
            public void onTransactionCompleted(HttpTransaction transaction) {
                broadcastEvent("transaction_completed", transaction);
            }
        };
    }

    public static InspectorServer getInstance() {
        if (instance == null) {
            synchronized (InspectorServer.class) {
                if (instance == null) {
                    instance = new InspectorServer();
                }
            }
        }
        return instance;
    }

    /**
     * Starts the server on the specified port.
     */
    public void start(int port) throws IOException {
        if (running.get()) {
            Log.w(TAG, "Server already running");
            return;
        }

        this.port = port;
        this.serverSocket = new ServerSocket(port);
        this.running.set(true);

        // Register for transaction events
        NetworkInspector.addListener(transactionListener);

        executor.submit(this::acceptLoop);
        Log.i(TAG, "Server started on port " + port);
    }

    /**
     * Stops the server.
     */
    public void stop() {
        running.set(false);

        // Unregister listener
        NetworkInspector.removeListener(transactionListener);

        // Close all SSE clients
        for (OutputStream client : sseClients) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        sseClients.clear();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server", e);
        }
    }

    /**
     * Returns the port the server is running on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Main accept loop.
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Error accepting connection", e);
                }
            }
        }
    }

    /**
     * Handles a single client connection.
     */
    private void handleClient(Socket client) {
        boolean isStreamingRequest = false;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            // Read HTTP request line
            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }

            Log.d(TAG, "Request: " + requestLine);

            // Parse request
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(out, 400, "Bad Request");
                client.close();
                return;
            }

            String method = parts[0];
            String path = parts[1];

            // Skip headers (read until empty line)
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Skip headers
            }

            // Route request (support GET and DELETE)
            if (!"GET".equals(method) && !"DELETE".equals(method)) {
                sendError(out, 405, "Method Not Allowed");
                client.close();
                return;
            }

            // Check if this is a streaming request (SSE)
            isStreamingRequest = "/events/stream".equals(path);

            routeRequest(method, path, out);

            // Only close if not a streaming request
            if (!isStreamingRequest) {
                client.close();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Routes the request to the appropriate handler.
     */
    private void routeRequest(String method, String path, OutputStream out) throws IOException {
        // Handle network request detail: /network/requests/{id}
        if (path.startsWith("/network/requests/") && path.length() > 18) {
            String requestId = path.substring(18);
            handleNetworkRequestById(requestId, out);
            return;
        }

        // Handle UI network detail: /ui/network/{id}
        if (path.startsWith("/ui/network/") && path.length() > 12) {
            String requestId = path.substring(12);
            handleNetworkDetailUI(requestId, out);
            return;
        }

        // Handle compose hit-test: /compose/select?x=N&y=N
        if (path.startsWith("/compose/select")) {
            handleComposeSelect(path, out);
            return;
        }

        // Handle compose hit-test all layers: /compose/select-all?x=N&y=N
        if (path.startsWith("/compose/select-all")) {
            handleComposeSelectAll(path, out);
            return;
        }

        // Handle compose find by ID: /compose/element/{id}
        if (path.startsWith("/compose/element/") && path.length() > 17) {
            String elementId = path.substring(17);
            // Strip query params if present
            int queryIndex = elementId.indexOf('?');
            if (queryIndex > 0) {
                elementId = elementId.substring(0, queryIndex);
            }
            handleComposeElementById(elementId, out);
            return;
        }

        switch (path) {
            case "/":
                handleHomeUI(out);
                break;
            case "/health":
                handleHealth(out);
                break;
            case "/ui":
            case "/ui/network":
                handleNetworkListUI(out);
                break;
            case "/compose/hierarchy":
                handleComposeHierarchy(out);
                break;
            case "/compose/semantics":
                handleComposeSemantics(out);
                break;
            case "/compose/tree":
                handleComposeTree(out);
                break;
            case "/compose/screenshot":
                handleComposeScreenshot(out);
                break;
            case "/network/requests":
                handleNetworkRequests(out);
                break;
            case "/network/clear":
                if ("DELETE".equals(method)) {
                    handleNetworkClear(out);
                } else {
                    sendError(out, 405, "Method Not Allowed");
                }
                break;
            case "/network/stats":
                handleNetworkStats(out);
                break;
            case "/events/stream":
                handleEventStream(out);
                return; // Don't close connection - SSE keeps it open
            default:
                sendError(out, 404, "Not Found");
        }
    }

    /**
     * GET /health - Health check endpoint.
     */
    private void handleHealth(OutputStream out) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("name", "ADT Sidekick");
        response.put("version", "1.2.0");
        response.put("port", port);
        response.put("sseClients", sseClients.size());
        response.put("endpoints", new String[]{
                "/health",
                "/events/stream",
                "/compose/hierarchy",
                "/compose/semantics",
                "/compose/tree",
                "/compose/screenshot",
                "/compose/select?x=N&y=N",
                "/compose/select-all?x=N&y=N",
                "/compose/element/{id}",
                "/network/requests",
                "/network/requests/{id}",
                "/network/clear",
                "/network/stats"
        });

        sendJson(out, 200, response);
    }

    /**
     * GET /compose/hierarchy - Full Compose UI hierarchy.
     */
    private void handleComposeHierarchy(OutputStream out) throws IOException {
        try {
            // Must run on main thread to access View hierarchy
            Object hierarchy = runOnMainThread(() -> ComposeInspector.captureHierarchy());

            if (hierarchy == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No Compose views found");
                error.put("hint", "Make sure the app has Compose UI visible");
                sendJson(out, 404, error);
                return;
            }

            sendJson(out, 200, hierarchy);

        } catch (Exception e) {
            Log.e(TAG, "Error capturing hierarchy", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /compose/semantics - Compose semantics tree only.
     */
    private void handleComposeSemantics(OutputStream out) throws IOException {
        try {
            Object semantics = runOnMainThread(() -> ComposeInspector.captureSemantics());

            if (semantics == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No Compose views found");
                sendJson(out, 404, error);
                return;
            }

            sendJson(out, 200, semantics);

        } catch (Exception e) {
            Log.e(TAG, "Error capturing semantics", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /compose/tree - Unified Compose tree with layout and semantics merged.
     */
    private void handleComposeTree(OutputStream out) throws IOException {
        try {
            Object tree = runOnMainThread(() -> ComposeInspector.captureUnifiedTree());

            if (tree == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No Compose views found");
                error.put("hint", "Make sure the app has Compose UI visible");
                sendJson(out, 404, error);
                return;
            }

            sendJson(out, 200, tree);

        } catch (Exception e) {
            Log.e(TAG, "Error capturing tree", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /compose/screenshot - Capture a screenshot of the current screen.
     */
    private void handleComposeScreenshot(OutputStream out) throws IOException {
        try {
            byte[] screenshot = runOnMainThread(() -> {
                android.app.Activity activity = getCurrentActivity();
                if (activity == null) {
                    return null;
                }
                return ComposeHitTester.captureScreenshot(activity.getWindow());
            });

            if (screenshot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Failed to capture screenshot");
                error.put("hint", "Make sure the app has a visible activity");
                sendJson(out, 500, error);
                return;
            }

            sendPng(out, screenshot);

        } catch (Exception e) {
            Log.e(TAG, "Error capturing screenshot", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /compose/select?x=N&y=N - Find the element at given coordinates.
     */
    private void handleComposeSelect(String path, OutputStream out) throws IOException {
        try {
            // Parse query parameters
            Map<String, String> params = parseQueryParams(path);
            String xStr = params.get("x");
            String yStr = params.get("y");

            if (xStr == null || yStr == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Missing required parameters");
                error.put("usage", "/compose/select?x=150&y=300");
                sendJson(out, 400, error);
                return;
            }

            int x = Integer.parseInt(xStr);
            int y = Integer.parseInt(yStr);

            ComposeHitTester.HitResult result = runOnMainThread(() ->
                ComposeHitTester.hitTest(x, y)
            );

            Map<String, Object> response = new HashMap<>();
            response.put("x", x);
            response.put("y", y);
            response.put("found", result.found);

            if (result.found) {
                response.put("element", result.element);
                response.put("ancestors", result.ancestors);
            }

            sendJson(out, 200, response);

        } catch (NumberFormatException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid coordinates - must be integers");
            sendJson(out, 400, error);
        } catch (Exception e) {
            Log.e(TAG, "Error in hit test", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /compose/select-all?x=N&y=N - Find all elements at given coordinates (all layers).
     */
    private void handleComposeSelectAll(String path, OutputStream out) throws IOException {
        try {
            Map<String, String> params = parseQueryParams(path);
            String xStr = params.get("x");
            String yStr = params.get("y");

            if (xStr == null || yStr == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Missing required parameters");
                error.put("usage", "/compose/select-all?x=150&y=300");
                sendJson(out, 400, error);
                return;
            }

            int x = Integer.parseInt(xStr);
            int y = Integer.parseInt(yStr);

            java.util.List<Map<String, Object>> elements = runOnMainThread(() ->
                ComposeHitTester.hitTestAll(x, y)
            );

            Map<String, Object> response = new HashMap<>();
            response.put("x", x);
            response.put("y", y);
            response.put("count", elements.size());
            response.put("elements", elements);

            sendJson(out, 200, response);

        } catch (NumberFormatException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid coordinates - must be integers");
            sendJson(out, 400, error);
        } catch (Exception e) {
            Log.e(TAG, "Error in hit test all", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /compose/element/{id} - Find element by ID.
     */
    private void handleComposeElementById(String elementId, OutputStream out) throws IOException {
        try {
            Map<String, Object> element = runOnMainThread(() ->
                ComposeHitTester.findById(elementId)
            );

            if (element == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Element not found");
                error.put("id", elementId);
                sendJson(out, 404, error);
                return;
            }

            sendJson(out, 200, element);

        } catch (Exception e) {
            Log.e(TAG, "Error finding element", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * Parses query parameters from a URL path.
     */
    private Map<String, String> parseQueryParams(String path) {
        Map<String, String> params = new HashMap<>();
        int queryIndex = path.indexOf('?');
        if (queryIndex < 0) {
            return params;
        }

        String queryString = path.substring(queryIndex + 1);
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex);
                String value = pair.substring(eqIndex + 1);
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * Gets the current foreground Activity.
     */
    private android.app.Activity getCurrentActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
            Object activityThread = currentMethod.invoke(null);

            java.lang.reflect.Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesMap = activitiesField.get(activityThread);

            if (activitiesMap instanceof Map) {
                for (Object activityRecord : ((Map<?, ?>) activitiesMap).values()) {
                    java.lang.reflect.Field activityField = activityRecord.getClass().getDeclaredField("activity");
                    activityField.setAccessible(true);
                    android.app.Activity activity = (android.app.Activity) activityField.get(activityRecord);

                    if (activity != null) {
                        java.lang.reflect.Field pausedField = activityRecord.getClass().getDeclaredField("paused");
                        pausedField.setAccessible(true);
                        boolean paused = pausedField.getBoolean(activityRecord);

                        if (!paused) {
                            return activity;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting current activity", e);
        }
        return null;
    }

    /**
     * Sends a PNG image response.
     */
    private void sendPng(OutputStream out, byte[] pngData) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: image/png\r\n");
        response.append("Content-Length: ").append(pngData.length).append("\r\n");
        response.append("Cache-Control: no-cache\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");

        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.write(pngData);
        out.flush();
    }

    // =========================================================================
    // Network Endpoints
    // =========================================================================

    /**
     * GET /network/requests - List all captured network transactions.
     */
    private void handleNetworkRequests(OutputStream out) throws IOException {
        try {
            java.util.List<HttpTransaction> transactions = NetworkInspector.getTransactions();

            java.util.List<Map<String, Object>> transactionList = new java.util.ArrayList<>();
            for (HttpTransaction tx : transactions) {
                transactionList.add(transactionToMap(tx, false));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("count", transactions.size());
            response.put("requests", transactionList);

            sendJson(out, 200, response);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network requests", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /network/requests/{id} - Get a single network transaction by ID.
     */
    private void handleNetworkRequestById(String requestId, OutputStream out) throws IOException {
        try {
            HttpTransaction tx = NetworkInspector.getTransaction(requestId);

            if (tx == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Request not found");
                error.put("id", requestId);
                sendJson(out, 404, error);
                return;
            }

            Map<String, Object> response = transactionToMap(tx, true);
            sendJson(out, 200, response);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network request", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * DELETE /network/clear - Clear all captured network transactions.
     */
    private void handleNetworkClear(OutputStream out) throws IOException {
        try {
            int count = NetworkInspector.getTransactionCount();
            NetworkInspector.clearTransactions();

            Map<String, Object> response = new HashMap<>();
            response.put("cleared", count);
            response.put("message", "Cleared " + count + " requests");

            sendJson(out, 200, response);

        } catch (Exception e) {
            Log.e(TAG, "Error clearing network requests", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /network/stats - Get network statistics.
     */
    private void handleNetworkStats(OutputStream out) throws IOException {
        try {
            java.util.List<HttpTransaction> transactions = NetworkInspector.getTransactions();

            int total = transactions.size();
            int pending = 0, completed = 0, failed = 0;
            long totalDuration = 0;
            long totalResponseSize = 0;

            for (HttpTransaction tx : transactions) {
                switch (tx.getStatus()) {
                    case PENDING:
                    case IN_PROGRESS:
                        pending++;
                        break;
                    case COMPLETED:
                        completed++;
                        totalDuration += tx.getDuration();
                        HttpResponse resp = tx.getResponse();
                        if (resp != null) {
                            totalResponseSize += resp.getBodySize();
                        }
                        break;
                    case FAILED:
                    case CANCELLED:
                        failed++;
                        break;
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("total", total);
            response.put("pending", pending);
            response.put("completed", completed);
            response.put("failed", failed);
            response.put("totalResponseSize", totalResponseSize);
            response.put("averageDuration", completed > 0 ? totalDuration / completed : 0);

            sendJson(out, 200, response);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network stats", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    // =========================================================================
    // SSE Streaming
    // =========================================================================

    /**
     * GET /events/stream - Server-Sent Events stream for real-time transaction updates.
     *
     * <p>Clients connect to this endpoint to receive real-time notifications
     * when network transactions start or complete.</p>
     */
    private void handleEventStream(OutputStream out) throws IOException {
        Log.i(TAG, "SSE client connected");

        // Send SSE headers
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n");
        headers.append("Content-Type: text/event-stream\r\n");
        headers.append("Cache-Control: no-cache\r\n");
        headers.append("Connection: keep-alive\r\n");
        headers.append("Access-Control-Allow-Origin: *\r\n");
        headers.append("\r\n");

        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Send initial connected event
        sendSseEvent(out, "connected", Map.of(
                "message", "Connected to event stream",
                "serverTime", System.currentTimeMillis()
        ));

        // Register this client for events
        sseClients.add(out);

        // Keep connection alive with heartbeats
        try {
            while (running.get() && sseClients.contains(out)) {
                Thread.sleep(15000); // 15 second heartbeat
                sendSseComment(out, "heartbeat");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Client disconnected
        } finally {
            sseClients.remove(out);
            Log.i(TAG, "SSE client disconnected");
        }
    }

    /**
     * Broadcasts an event to all connected SSE clients.
     */
    private void broadcastEvent(String eventType, HttpTransaction tx) {
        if (sseClients.isEmpty()) {
            return;
        }

        Map<String, Object> data = transactionToMap(tx, false);
        data.put("eventType", eventType);

        for (OutputStream client : sseClients) {
            try {
                sendSseEvent(client, eventType, data);
            } catch (IOException e) {
                // Client disconnected, will be cleaned up
                sseClients.remove(client);
            }
        }
    }

    /**
     * Sends an SSE event to a client.
     */
    private void sendSseEvent(OutputStream out, String event, Object data) throws IOException {
        String json = gson.toJson(data);
        // SSE format: event: <type>\ndata: <json>\n\n
        String sseMessage = "event: " + event + "\ndata: " + json + "\n\n";
        synchronized (out) {
            out.write(sseMessage.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Sends an SSE comment (for keepalive).
     */
    private void sendSseComment(OutputStream out, String comment) throws IOException {
        String sseComment = ": " + comment + "\n\n";
        synchronized (out) {
            out.write(sseComment.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Converts an HttpTransaction to a Map for JSON serialization.
     *
     * @param tx             the transaction to convert
     * @param includeDetails whether to include headers and bodies (for detail view)
     */
    private Map<String, Object> transactionToMap(HttpTransaction tx, boolean includeDetails) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", tx.getId());
        map.put("status", tx.getStatus().name());
        map.put("startTime", tx.getStartTime());
        map.put("endTime", tx.getEndTime());
        map.put("duration", tx.getDuration());
        map.put("source", tx.getSource());

        if (tx.getError() != null) {
            map.put("error", tx.getError());
        }

        // Request details
        HttpRequest request = tx.getRequest();
        if (request != null) {
            map.put("url", request.getUrl());
            map.put("method", request.getMethod());
            if (includeDetails) {
                map.put("requestHeaders", headersToMap(request.getHeaders()));
                map.put("requestContentType", request.getContentType());
                map.put("requestBodySize", request.getBodySize());
                map.put("requestBody", request.getBody());
                if (request.hasExternalBody()) {
                    map.put("requestBodyRef", request.getBodyRef().getPath());
                }
            }
        }

        // Response details
        HttpResponse response = tx.getResponse();
        if (response != null) {
            map.put("responseCode", response.getStatusCode());
            map.put("responseBodySize", response.getBodySize());
            if (includeDetails) {
                map.put("responseMessage", response.getStatusMessage());
                map.put("protocol", response.getProtocol());
                map.put("responseHeaders", headersToMap(response.getHeaders()));
                map.put("responseContentType", response.getContentType());
                map.put("responseBody", response.getBody());
                if (response.hasExternalBody()) {
                    map.put("responseBodyRef", response.getBodyRef().getPath());
                }
            }
        }

        return map;
    }

    /**
     * Converts a list of HttpHeader to a Map for JSON serialization.
     */
    private Map<String, String> headersToMap(java.util.List<HttpHeader> headers) {
        Map<String, String> map = new HashMap<>();
        if (headers != null) {
            for (HttpHeader header : headers) {
                // Note: This will overwrite duplicate headers with the last value
                map.put(header.getName(), header.getValue());
            }
        }
        return map;
    }

    // =========================================================================
    // HTML UI Endpoints
    // =========================================================================

    /**
     * GET / - Home page with links to UI sections.
     */
    private void handleHomeUI(OutputStream out) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        html.append("<title>ADT Sidekick</title>");
        html.append(getCommonStyles());
        html.append("</head><body>");
        html.append("<div class='container'>");
        html.append("<h1>🔧 ADT Sidekick</h1>");
        html.append("<p class='subtitle'>Android Development Tools Inspector</p>");
        html.append("<div class='card'>");
        html.append("<h2>📡 Network</h2>");
        html.append("<p>Monitor HTTP requests and responses</p>");
        html.append("<a href='/ui/network' class='btn'>View Network Traffic</a>");
        html.append("</div>");
        html.append("<div class='card'>");
        html.append("<h2>🎨 Compose</h2>");
        html.append("<p>Inspect Compose UI hierarchy</p>");
        html.append("<a href='/compose/tree' class='btn btn-secondary'>View JSON</a>");
        html.append("</div>");
        html.append("<div class='card'>");
        html.append("<h2>📊 API Endpoints</h2>");
        html.append("<ul>");
        html.append("<li><code>/network/requests</code> - List requests</li>");
        html.append("<li><code>/network/requests/{id}</code> - Request details</li>");
        html.append("<li><code>/network/stats</code> - Statistics</li>");
        html.append("<li><code>/compose/tree</code> - Compose hierarchy</li>");
        html.append("</ul>");
        html.append("</div>");
        html.append("</div></body></html>");

        sendHtml(out, 200, html.toString());
    }

    /**
     * GET /ui/network - Network requests list page.
     */
    private void handleNetworkListUI(OutputStream out) throws IOException {
        java.util.List<HttpTransaction> transactions = NetworkInspector.getTransactions();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        html.append("<title>Network - ADT Sidekick</title>");
        html.append(getCommonStyles());
        html.append("<style>");
        html.append(".request-row { display: flex; padding: 12px; border-bottom: 1px solid #333; cursor: pointer; }");
        html.append(".request-row:hover { background: #2a2a2a; }");
        html.append(".method { width: 60px; font-weight: bold; }");
        html.append(".method.GET { color: #4caf50; }");
        html.append(".method.POST { color: #ff9800; }");
        html.append(".method.PUT { color: #2196f3; }");
        html.append(".method.DELETE { color: #f44336; }");
        html.append(".url { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #ccc; }");
        html.append(".status { width: 50px; text-align: center; }");
        html.append(".status.s2xx { color: #4caf50; }");
        html.append(".status.s3xx { color: #ff9800; }");
        html.append(".status.s4xx, .status.s5xx { color: #f44336; }");
        html.append(".duration { width: 70px; text-align: right; color: #888; }");
        html.append(".empty { text-align: center; padding: 40px; color: #666; }");
        html.append(".toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }");
        html.append("</style>");
        html.append("</head><body>");
        html.append("<div class='container'>");
        html.append("<div class='toolbar'>");
        html.append("<h1>📡 Network Traffic</h1>");
        html.append("<div>");
        html.append("<a href='/' class='btn btn-secondary'>Home</a> ");
        html.append("<button onclick='location.reload()' class='btn'>Refresh</button>");
        html.append("</div></div>");

        if (transactions.isEmpty()) {
            html.append("<div class='card empty'>No network requests captured yet</div>");
        } else {
            html.append("<div class='card' style='padding: 0;'>");
            // Reverse order to show newest first
            for (int i = transactions.size() - 1; i >= 0; i--) {
                HttpTransaction tx = transactions.get(i);
                HttpRequest req = tx.getRequest();
                HttpResponse resp = tx.getResponse();

                String method = req != null ? req.getMethod() : "?";
                String url = req != null ? req.getUrl() : "Unknown";
                int code = resp != null ? resp.getStatusCode() : 0;
                String statusClass = code >= 200 && code < 300 ? "s2xx" :
                        code >= 300 && code < 400 ? "s3xx" :
                                code >= 400 && code < 500 ? "s4xx" : "s5xx";

                html.append("<a href='/ui/network/").append(tx.getId()).append("' class='request-row' style='text-decoration:none;'>");
                html.append("<span class='method ").append(method).append("'>").append(method).append("</span>");
                html.append("<span class='url'>").append(escapeHtml(url)).append("</span>");
                html.append("<span class='status ").append(statusClass).append("'>").append(code > 0 ? code : "-").append("</span>");
                html.append("<span class='duration'>").append(tx.getDuration()).append("ms</span>");
                html.append("</a>");
            }
            html.append("</div>");
        }

        html.append("<p style='color:#666; font-size:12px; margin-top:16px;'>").append(transactions.size()).append(" requests</p>");
        html.append("</div></body></html>");

        sendHtml(out, 200, html.toString());
    }

    /**
     * GET /ui/network/{id} - Network request detail page.
     */
    private void handleNetworkDetailUI(String requestId, OutputStream out) throws IOException {
        HttpTransaction tx = NetworkInspector.getTransaction(requestId);

        if (tx == null) {
            sendHtml(out, 404, "<html><body><h1>Request not found</h1><a href='/ui/network'>Back</a></body></html>");
            return;
        }

        HttpRequest req = tx.getRequest();
        HttpResponse resp = tx.getResponse();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        html.append("<title>Request Detail - ADT Sidekick</title>");
        html.append(getCommonStyles());
        html.append("<style>");
        html.append(".section { margin-bottom: 24px; }");
        html.append(".section h3 { color: #888; font-size: 12px; text-transform: uppercase; margin-bottom: 8px; }");
        html.append(".header-row { display: flex; padding: 4px 0; border-bottom: 1px solid #333; font-size: 13px; }");
        html.append(".header-name { width: 200px; color: #888; }");
        html.append(".header-value { flex: 1; word-break: break-all; }");
        html.append(".body-content { background: #1a1a1a; padding: 12px; border-radius: 4px; ");
        html.append("  font-family: monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 400px; overflow: auto; }");
        html.append(".meta { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 16px; }");
        html.append(".meta-item { }");
        html.append(".meta-label { color: #666; font-size: 11px; }");
        html.append(".meta-value { font-size: 16px; }");
        html.append(".tabs { display: flex; border-bottom: 1px solid #333; margin-bottom: 16px; }");
        html.append(".tab { padding: 8px 16px; cursor: pointer; color: #888; }");
        html.append(".tab.active { color: #fff; border-bottom: 2px solid #4caf50; }");
        html.append(".tab-content { display: none; }");
        html.append(".tab-content.active { display: block; }");
        html.append("</style>");
        html.append("</head><body>");
        html.append("<div class='container'>");

        // Header
        String method = req != null ? req.getMethod() : "?";
        String url = req != null ? req.getUrl() : "Unknown";
        int code = resp != null ? resp.getStatusCode() : 0;

        html.append("<div style='margin-bottom: 16px;'>");
        html.append("<a href='/ui/network' style='color: #4caf50;'>← Back to list</a>");
        html.append("</div>");

        html.append("<div class='card'>");
        html.append("<h2 style='margin-bottom: 8px;'>");
        html.append("<span style='color:").append(method.equals("GET") ? "#4caf50" : "#ff9800").append(";'>").append(method).append("</span> ");
        html.append("<span style='color:").append(code >= 200 && code < 300 ? "#4caf50" : "#f44336").append(";'>").append(code).append("</span>");
        html.append("</h2>");
        html.append("<p style='word-break:break-all; color:#ccc;'>").append(escapeHtml(url)).append("</p>");

        // Meta info
        html.append("<div class='meta'>");
        html.append("<div class='meta-item'><div class='meta-label'>Duration</div><div class='meta-value'>").append(tx.getDuration()).append("ms</div></div>");
        if (resp != null) {
            html.append("<div class='meta-item'><div class='meta-label'>Response Size</div><div class='meta-value'>").append(formatBytes(resp.getBodySize())).append("</div></div>");
            html.append("<div class='meta-item'><div class='meta-label'>Protocol</div><div class='meta-value'>").append(resp.getProtocol()).append("</div></div>");
        }
        html.append("<div class='meta-item'><div class='meta-label'>Status</div><div class='meta-value'>").append(tx.getStatus()).append("</div></div>");
        html.append("</div>");
        html.append("</div>");

        // Tabs
        html.append("<div class='tabs'>");
        html.append("<div class='tab active' onclick='showTab(\"request\")'>Request</div>");
        html.append("<div class='tab' onclick='showTab(\"response\")'>Response</div>");
        html.append("</div>");

        // Request tab
        html.append("<div id='request' class='tab-content active'>");
        if (req != null) {
            // Request headers
            html.append("<div class='section'>");
            html.append("<h3>Headers</h3>");
            java.util.List<HttpHeader> reqHeaders = req.getHeaders();
            if (reqHeaders != null && !reqHeaders.isEmpty()) {
                for (HttpHeader h : reqHeaders) {
                    html.append("<div class='header-row'>");
                    html.append("<span class='header-name'>").append(escapeHtml(h.getName())).append("</span>");
                    html.append("<span class='header-value'>").append(escapeHtml(h.getValue())).append("</span>");
                    html.append("</div>");
                }
            } else {
                html.append("<p style='color:#666;'>No headers</p>");
            }
            html.append("</div>");

            // Request body
            String reqBody = req.getBody();
            if (reqBody != null && !reqBody.isEmpty()) {
                html.append("<div class='section'>");
                html.append("<h3>Body</h3>");
                html.append("<div class='body-content'>").append(escapeHtml(reqBody)).append("</div>");
                html.append("</div>");
            }
        }
        html.append("</div>");

        // Response tab
        html.append("<div id='response' class='tab-content'>");
        if (resp != null) {
            // Response headers
            html.append("<div class='section'>");
            html.append("<h3>Headers</h3>");
            java.util.List<HttpHeader> respHeaders = resp.getHeaders();
            if (respHeaders != null && !respHeaders.isEmpty()) {
                for (HttpHeader h : respHeaders) {
                    html.append("<div class='header-row'>");
                    html.append("<span class='header-name'>").append(escapeHtml(h.getName())).append("</span>");
                    html.append("<span class='header-value'>").append(escapeHtml(h.getValue())).append("</span>");
                    html.append("</div>");
                }
            }
            html.append("</div>");

            // Response body
            String respBody = resp.getBody();
            if (respBody != null && !respBody.isEmpty()) {
                html.append("<div class='section'>");
                html.append("<h3>Body</h3>");
                html.append("<div class='body-content'>").append(escapeHtml(respBody)).append("</div>");
                html.append("</div>");
            }
        } else {
            html.append("<p style='color:#666;'>No response received</p>");
        }
        html.append("</div>");

        // JavaScript for tabs
        html.append("<script>");
        html.append("function showTab(name) {");
        html.append("  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));");
        html.append("  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));");
        html.append("  event.target.classList.add('active');");
        html.append("  document.getElementById(name).classList.add('active');");
        html.append("}");
        html.append("</script>");

        html.append("</div></body></html>");

        sendHtml(out, 200, html.toString());
    }

    /**
     * Returns common CSS styles for UI pages.
     */
    private String getCommonStyles() {
        return "<style>" +
                "* { box-sizing: border-box; margin: 0; padding: 0; }" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                "  background: #1a1a1a; color: #fff; line-height: 1.5; }" +
                ".container { max-width: 900px; margin: 0 auto; padding: 20px; }" +
                "h1 { font-size: 24px; margin-bottom: 8px; }" +
                ".subtitle { color: #888; margin-bottom: 24px; }" +
                ".card { background: #222; border-radius: 8px; padding: 16px; margin-bottom: 16px; }" +
                ".card h2 { font-size: 18px; margin-bottom: 8px; }" +
                ".card p { color: #888; margin-bottom: 12px; }" +
                ".card ul { margin-left: 20px; color: #888; }" +
                ".card li { margin: 4px 0; }" +
                "code { background: #333; padding: 2px 6px; border-radius: 4px; font-size: 13px; }" +
                ".btn { display: inline-block; padding: 8px 16px; background: #4caf50; color: #fff; " +
                "  text-decoration: none; border-radius: 4px; border: none; cursor: pointer; font-size: 14px; }" +
                ".btn:hover { background: #45a049; }" +
                ".btn-secondary { background: #444; }" +
                ".btn-secondary:hover { background: #555; }" +
                "</style>";
    }

    /**
     * Escapes HTML special characters.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Formats bytes to human readable string.
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Sends an HTML response.
     */
    private void sendHtml(OutputStream out, int statusCode, String html) throws IOException {
        String status = statusCode == 200 ? "OK" : statusCode == 404 ? "Not Found" : "Error";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);

        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(" ").append(status).append("\r\n");
        response.append("Content-Type: text/html; charset=utf-8\r\n");
        response.append("Content-Length: ").append(body.length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");

        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Runs a callable on the main thread and waits for result.
     */
    private <T> T runOnMainThread(java.util.concurrent.Callable<T> callable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callable.call();
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout waiting for main thread");
        }

        if (error.get() != null) {
            throw error.get();
        }

        return result.get();
    }

    /**
     * Sends a JSON response.
     */
    private void sendJson(OutputStream out, int statusCode, Object body) throws IOException {
        String json = gson.toJson(body);
        String statusText = statusCode == 200 ? "OK" : "Error";

        String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                json;

        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Sends an error response.
     */
    private void sendError(OutputStream out, int statusCode, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("status", statusCode);
        sendJson(out, statusCode, error);
    }
}
