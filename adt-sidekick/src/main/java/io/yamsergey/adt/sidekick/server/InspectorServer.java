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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.yamsergey.adt.sidekick.compose.ComposeInspector;
import io.yamsergey.adt.sidekick.network.NetworkInspector;
import io.yamsergey.adt.sidekick.network.NetworkRequest;

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

    private ServerSocket serverSocket;
    private int port;

    private InspectorServer() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
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

        executor.submit(this::acceptLoop);
        Log.i(TAG, "Server started on port " + port);
    }

    /**
     * Stops the server.
     */
    public void stop() {
        running.set(false);
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
        try (client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream out = client.getOutputStream()) {

            // Read HTTP request line
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }

            Log.d(TAG, "Request: " + requestLine);

            // Parse request
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(out, 400, "Bad Request");
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
                return;
            }

            routeRequest(method, path, out);

        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
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

        switch (path) {
            case "/":
            case "/health":
                handleHealth(out);
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
        response.put("version", "1.1.0");
        response.put("port", port);
        response.put("endpoints", new String[]{
                "/health",
                "/compose/hierarchy",
                "/compose/semantics",
                "/compose/tree",
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

    // =========================================================================
    // Network Endpoints
    // =========================================================================

    /**
     * GET /network/requests - List all captured network requests.
     */
    private void handleNetworkRequests(OutputStream out) throws IOException {
        try {
            java.util.List<NetworkRequest> requests = NetworkInspector.getRequests();

            java.util.List<Map<String, Object>> requestList = new java.util.ArrayList<>();
            for (NetworkRequest request : requests) {
                requestList.add(networkRequestToMap(request, false));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("count", requests.size());
            response.put("requests", requestList);

            sendJson(out, 200, response);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network requests", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /network/requests/{id} - Get a single network request by ID.
     */
    private void handleNetworkRequestById(String requestId, OutputStream out) throws IOException {
        try {
            NetworkRequest request = NetworkInspector.getRequest(requestId);

            if (request == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Request not found");
                error.put("id", requestId);
                sendJson(out, 404, error);
                return;
            }

            Map<String, Object> response = networkRequestToMap(request, true);
            sendJson(out, 200, response);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network request", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * DELETE /network/clear - Clear all captured network requests.
     */
    private void handleNetworkClear(OutputStream out) throws IOException {
        try {
            int count = NetworkInspector.getRequestCount();
            NetworkInspector.clearRequests();

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
            java.util.List<NetworkRequest> requests = NetworkInspector.getRequests();

            int total = requests.size();
            int pending = 0, completed = 0, failed = 0;
            long totalDuration = 0;
            long totalResponseSize = 0;

            for (NetworkRequest request : requests) {
                switch (request.getStatus()) {
                    case PENDING:
                    case IN_PROGRESS:
                        pending++;
                        break;
                    case COMPLETED:
                        completed++;
                        totalDuration += request.getDuration();
                        totalResponseSize += request.getResponseBodySize();
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

    /**
     * Converts a NetworkRequest to a Map for JSON serialization.
     *
     * @param request     the request to convert
     * @param includeBody whether to include request/response bodies
     */
    private Map<String, Object> networkRequestToMap(NetworkRequest request, boolean includeBody) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", request.getId());
        map.put("url", request.getUrl());
        map.put("method", request.getMethod());
        map.put("status", request.getStatus().name());
        map.put("startTime", request.getStartTime());
        map.put("endTime", request.getEndTime());
        map.put("duration", request.getDuration());
        map.put("responseCode", request.getResponseCode());
        map.put("responseMessage", request.getResponseMessage());
        map.put("responseBodySize", request.getResponseBodySize());
        map.put("protocol", request.getProtocol());
        map.put("source", request.getSource());

        if (request.getError() != null) {
            map.put("error", request.getError());
        }

        // Always include headers
        map.put("requestHeaders", request.getRequestHeaders());
        map.put("responseHeaders", request.getResponseHeaders());
        map.put("requestContentType", request.getRequestContentType());
        map.put("responseContentType", request.getResponseContentType());

        // Only include bodies in detail view
        if (includeBody) {
            map.put("requestBody", request.getRequestBody());
            map.put("responseBody", request.getResponseBody());
        }

        return map;
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
