package io.yamsergey.dta.sidekick.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import io.yamsergey.dta.sidekick.SidekickLog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.yamsergey.dta.sidekick.compose.ComposeInspector;
import io.yamsergey.dta.sidekick.compose.ComposeHitTester;
import io.yamsergey.dta.sidekick.customtabs.CustomTabEvent;
import io.yamsergey.dta.sidekick.customtabs.CustomTabsInspector;
import io.yamsergey.dta.sidekick.mock.MockConfig;
import io.yamsergey.dta.sidekick.mock.MockDirection;
import io.yamsergey.dta.sidekick.mock.MockHttpResponse;
import io.yamsergey.dta.sidekick.mock.MockManager;
import io.yamsergey.dta.sidekick.mock.MockRule;
import io.yamsergey.dta.sidekick.mock.MockType;
import io.yamsergey.dta.sidekick.mock.MockWebSocketMessage;
import io.yamsergey.dta.sidekick.network.BodyReference;
import io.yamsergey.dta.sidekick.network.BodyStorage;
import io.yamsergey.dta.sidekick.network.HttpHeader;
import io.yamsergey.dta.sidekick.network.HttpRequest;
import io.yamsergey.dta.sidekick.network.HttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;
import io.yamsergey.dta.sidekick.network.NetworkInspector;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * HTTP server for ADT Sidekick inspection endpoints using Unix domain sockets.
 *
 * <p>Provides REST-like endpoints for inspecting various Android components.
 * Uses abstract namespace Unix domain socket for secure local communication.</p>
 *
 * <p>Socket name format: {@code dta_sidekick_{package_name}}</p>
 */
public class InspectorServer {

    private static final String TAG = "InspectorServer";
    private static final String SOCKET_PREFIX = "dta_sidekick_";
    private static final String VERSION = loadVersion();
    private static volatile InspectorServer instance;

    /**
     * Loads the version from the generated version.properties file.
     */
    private static String loadVersion() {
        try (java.io.InputStream is = InspectorServer.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                return props.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to load version.properties", e);
        }
        return "unknown";
    }

    private final Gson gson;
    private final Gson gsonCompact;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // SSE streaming clients
    private final Set<OutputStream> sseClients = ConcurrentHashMap.newKeySet();
    private final NetworkInspector.TransactionListener transactionListener;
    private final CustomTabsInspector.CustomTabEventListener customTabEventListener;

    // Selection state (multi-selection support)
    private final List<Map<String, Object>> selectedElements = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> selectedNetworkRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> selectedWebSocketMessages = Collections.synchronizedList(new ArrayList<>()); // each: { connectionId, messageIndex, message }

    // CDP capture ack mechanism
    private final ConcurrentHashMap<String, CountDownLatch> pendingCdpAcks = new ConcurrentHashMap<>();
    private volatile boolean cdpCaptureRequested = false;

    private volatile LocalServerSocket serverSocket;
    private volatile String socketName;
    private volatile String packageName;
    private final AtomicBoolean startCalled = new AtomicBoolean(false);

    private InspectorServer() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.gsonCompact = new Gson();
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

        // Create listener for Custom Tab events
        // When cdpCaptureRequested, broadcast is handled by waitForCdpAckIfNeeded instead
        this.customTabEventListener = event -> {
            if (!cdpCaptureRequested) {
                broadcastCustomTabEvent(event);
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
     * Blocks the calling thread until a CDP ack is received for the given event,
     * or until the 2-second timeout expires. This is called from the JVMTI hook
     * to delay the Custom Tab launch, giving the daemon time to attach CDP.
     *
     * <p>If CDP capture is not armed or no SSE clients are connected, returns immediately.</p>
     */
    public void waitForCdpAckIfNeeded(CustomTabEvent event) {
        try {
            if (!cdpCaptureRequested || sseClients.isEmpty()) {
                return;
            }
            CountDownLatch latch = new CountDownLatch(1);
            pendingCdpAcks.put(event.getId(), latch);
            try {
                broadcastCustomTabEvent(event);
                latch.await(2, TimeUnit.SECONDS);
            } finally {
                pendingCdpAcks.remove(event.getId());
            }
        } catch (Exception e) {
            SidekickLog.w(TAG, "CDP ack wait interrupted", e);
        }
    }

    /**
     * Starts the server on a Unix domain socket.
     *
     * @param packageName the app's package name, used to create unique socket name
     */
    public void start(String packageName) throws IOException {
        // Prevent multiple concurrent start calls
        if (!startCalled.compareAndSet(false, true)) {
            SidekickLog.w(TAG, "Server start already in progress");
            return;
        }

        if (running.get()) {
            SidekickLog.w(TAG, "Server already running");
            startCalled.set(false); // Reset flag - not starting, already running
            return;
        }

        this.packageName = packageName;
        this.socketName = SOCKET_PREFIX + packageName;
        
        // Close any existing server socket first to prevent duplicates
        if (this.serverSocket != null) {
            try {
                this.serverSocket.close();
            } catch (IOException e) {
                SidekickLog.w(TAG, "Error closing existing server socket", e);
            }
            this.serverSocket = null;
        }
        
        try {
            this.serverSocket = new LocalServerSocket(socketName);
        } catch (IOException e) {
            // If socket creation fails, it might be because the socket already exists
            // This can happen if the app was killed without proper cleanup
            SidekickLog.e(TAG, "Failed to create server socket: " + socketName, e);
            
            // Try to clean up and retry once
            try {
                // Wait a bit for the old socket to be cleaned up by the system
                Thread.sleep(100);
                this.serverSocket = new LocalServerSocket(socketName);
                SidekickLog.i(TAG, "Server socket created on retry");
            } catch (Exception retryException) {
                SidekickLog.e(TAG, "Failed to create server socket on retry", retryException);
                startCalled.set(false); // Reset flag on failure
                throw new IOException("Failed to create server socket: " + socketName, retryException);
            }
        }
        
        this.running.set(true);

        // Register for transaction events
        NetworkInspector.addListener(transactionListener);

        // Register for Custom Tab events
        CustomTabsInspector.addListener(customTabEventListener);

        executor.submit(this::acceptLoop);
        SidekickLog.i(TAG, "Server started on socket: " + socketName);
    }

    /**
     * Stops the server.
     *
     * <p>Note: After stopping, the server cannot be restarted because the executor
     * is shut down. This is by design - the server is expected to run for the app's
     * entire lifecycle as a singleton.</p>
     */
    public void stop() {
        if (!running.get()) {
            SidekickLog.d(TAG, "Server not running, nothing to stop");
            return;
        }

        SidekickLog.i(TAG, "Stopping server on socket: " + socketName);
        running.set(false);
        startCalled.set(false); // Reset to allow potential restart (though executor limits this)

        // Unregister listeners
        NetworkInspector.removeListener(transactionListener);
        CustomTabsInspector.removeListener(customTabEventListener);

        // Close all SSE clients
        for (OutputStream client : sseClients) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        sseClients.clear();

        // Close server socket first to unblock accept()
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
                SidekickLog.i(TAG, "Server socket closed successfully");
            }
        } catch (IOException e) {
            SidekickLog.e(TAG, "Error closing server socket", e);
        }

        // Shutdown executor - this prevents restart but ensures clean shutdown
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            SidekickLog.e(TAG, "Error shutting down executor", e);
        }
    }

    /**
     * Returns the socket name the server is listening on.
     */
    public String getSocketName() {
        return socketName;
    }

    /**
     * Returns the package name of the app.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Main accept loop.
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                LocalSocket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    SidekickLog.e(TAG, "Error accepting connection", e);
                }
            }
        }
    }

    /**
     * Handles a single client connection.
     */
    private void handleClient(LocalSocket client) {
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

            SidekickLog.d(TAG, "Request: " + requestLine);

            // Parse request
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(out, 400, "Bad Request");
                client.close();
                return;
            }

            String method = parts[0];
            String path = parts[1];

            // Read headers and body
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // Read body if present - must loop since read() may return fewer chars than requested
            String body = null;
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(bodyChars, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                if (totalRead > 0) {
                    body = new String(bodyChars, 0, totalRead);
                }
            }

            // Route request (support GET, POST, PUT, and DELETE)
            if (!"GET".equals(method) && !"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) {
                sendError(out, 405, "Method Not Allowed");
                client.close();
                return;
            }

            // Check if this is a streaming request (SSE)
            isStreamingRequest = "/events/stream".equals(path);

            routeRequest(method, path, body, out);

            // Only close if not a streaming request
            if (!isStreamingRequest) {
                client.close();
            }

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error handling client", e);
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Routes the request to the appropriate handler.
     */
    private void routeRequest(String method, String path, String body, OutputStream out) throws IOException {
        // Handle network request body: /network/requests/{id}/body
        if (path.startsWith("/network/requests/") && path.contains("/body")) {
            String requestId = path.substring(18, path.indexOf("/body"));
            handleNetworkRequestBody(requestId, out);
            return;
        }

        // Handle network request detail: /network/requests/{id}
        if (path.startsWith("/network/requests/") && path.length() > 18) {
            String requestId = path.substring(18);
            handleNetworkRequestById(requestId, out);
            return;
        }

        // Handle WebSocket connection detail: /websocket/connections/{id}
        if (path.startsWith("/websocket/connections/") && path.length() > 23) {
            String connectionId = path.substring(23);
            handleWebSocketConnectionById(connectionId, out);
            return;
        }

        // Handle mock rule by ID: /mock/rules/{id}
        if (path.startsWith("/mock/rules/") && path.length() > 12) {
            String ruleId = path.substring(12);
            if ("GET".equals(method)) {
                handleMockRuleById(ruleId, out);
            } else if ("PUT".equals(method)) {
                handleUpdateMockRule(ruleId, body, out);
            } else if ("DELETE".equals(method)) {
                handleDeleteMockRule(ruleId, out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        // Handle mock rules list: /mock/rules
        if (path.equals("/mock/rules")) {
            if ("GET".equals(method)) {
                handleMockRulesList(out);
            } else if ("POST".equals(method)) {
                handleCreateMockRule(body, out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        // Handle mock config: /mock/config
        if (path.equals("/mock/config")) {
            if ("GET".equals(method)) {
                handleGetMockConfig(out);
            } else if ("PUT".equals(method)) {
                handleUpdateMockConfig(body, out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        // Handle create mock from request: /mock/from-request/{id}
        if (path.startsWith("/mock/from-request/") && path.length() > 19) {
            String requestId = path.substring(19);
            if ("POST".equals(method)) {
                handleCreateMockFromRequest(requestId, out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        // Handle create mock from WebSocket message: /mock/from-message/{id}
        if (path.startsWith("/mock/from-message/") && path.length() > 19) {
            String messageId = path.substring(19);
            if ("POST".equals(method)) {
                handleCreateMockFromMessage(messageId, out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
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

        // Handle selection endpoints (multi-selection support)
        // Query param: action=add|remove|clear (default: add for POST)
        Map<String, String> selectionParams = parseQueryParams(path);
        String action = selectionParams.getOrDefault("action", "add");
        // Strip query params from path for matching
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        if (cleanPath.equals("/selection/element")) {
            if ("GET".equals(method)) {
                handleGetSelectionElements(out);
            } else if ("POST".equals(method)) {
                handleModifySelectionElements(body, action, out);
            } else if ("DELETE".equals(method)) {
                handleModifySelectionElements(body, "clear", out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        if (cleanPath.equals("/selection/network")) {
            if ("GET".equals(method)) {
                handleGetSelectionNetwork(out);
            } else if ("POST".equals(method)) {
                handleModifySelectionNetwork(body, action, out);
            } else if ("DELETE".equals(method)) {
                handleModifySelectionNetwork(body, "clear", out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        if (cleanPath.equals("/selection/websocket-message")) {
            if ("GET".equals(method)) {
                handleGetSelectionWebSocketMessage(out);
            } else if ("POST".equals(method)) {
                handleModifySelectionWebSocketMessage(body, action, out);
            } else if ("DELETE".equals(method)) {
                handleModifySelectionWebSocketMessage(body, "clear", out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
            return;
        }

        // Handle CDP capture control endpoints
        if (path.equals("/customtabs/request-cdp-capture") && "POST".equals(method)) {
            handleRequestCdpCapture(out);
            return;
        }
        if (path.equals("/customtabs/release-cdp-capture") && "POST".equals(method)) {
            handleReleaseCdpCapture(out);
            return;
        }
        if (path.startsWith("/customtabs/ack/") && "POST".equals(method)) {
            String eventId = path.substring("/customtabs/ack/".length());
            handleCdpAck(eventId, out);
            return;
        }

        // Handle Custom Tabs endpoints
        if (path.equals("/customtabs/events")) {
            if ("GET".equals(method)) {
                handleCustomTabsEvents(out);
            } else if ("DELETE".equals(method)) {
                handleCustomTabsClear(out);
            } else {
                sendError(out, 405, "Method Not Allowed");
            }
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
            case "/compose/screenshot":
                handleComposeScreenshot(out);
                break;
            case "/network/requests":
                handleNetworkRequests(out);
                break;
            case "/network/transactions":
                if ("POST".equals(method)) {
                    handleNetworkRecordTransaction(body, out);
                } else {
                    sendError(out, 405, "Method Not Allowed");
                }
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
            case "/websocket/connections":
                handleWebSocketConnections(out);
                break;
            case "/websocket/clear":
                if ("DELETE".equals(method)) {
                    handleWebSocketClear(out);
                } else {
                    sendError(out, 405, "Method Not Allowed");
                }
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
        response.put("version", VERSION);
        response.put("socketName", socketName);
        response.put("packageName", packageName);
        response.put("sseClients", sseClients.size());
        response.put("cdpCaptureRequested", cdpCaptureRequested);
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
                "/network/transactions",
                "/network/clear",
                "/network/stats",
                "/websocket/connections",
                "/websocket/connections/{id}",
                "/websocket/clear",
                "/customtabs/events",
                "/customtabs/request-cdp-capture",
                "/customtabs/release-cdp-capture",
                "/customtabs/ack/{eventId}",
                "/selection/element",
                "/selection/network",
                "/selection/websocket-message",
                "/mock/rules",
                "/mock/rules/{id}",
                "/mock/config",
                "/mock/from-request/{id}",
                "/mock/from-message/{id}"
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
            SidekickLog.e(TAG, "Error capturing hierarchy", e);
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
            SidekickLog.e(TAG, "Error capturing semantics", e);
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
            SidekickLog.e(TAG, "Error capturing tree", e);
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
            // Get the window on the main thread, but capture the screenshot off
            // the main thread. PixelCopy posts its callback to the main looper,
            // which would deadlock if we're already blocking the main thread.
            android.view.Window window = runOnMainThread(() -> {
                android.app.Activity activity = getCurrentActivity();
                return activity != null ? activity.getWindow() : null;
            });
            byte[] screenshot = window != null
                    ? ComposeHitTester.captureScreenshot(window)
                    : null;

            if (screenshot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Failed to capture screenshot");
                error.put("hint", "Make sure the app has a visible activity");
                sendJson(out, 500, error);
                return;
            }

            sendPng(out, screenshot);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error capturing screenshot", e);
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
            SidekickLog.e(TAG, "Error in hit test", e);
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
            SidekickLog.e(TAG, "Error in hit test all", e);
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
            SidekickLog.e(TAG, "Error finding element", e);
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
            SidekickLog.e(TAG, "Error getting current activity", e);
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
            SidekickLog.e(TAG, "Error getting network requests", e);
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
            SidekickLog.e(TAG, "Error getting network request", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /network/requests/{id}/body - Get the response body for a request.
     * Returns base64-encoded body for binary content, or raw text for text content.
     */
    private void handleNetworkRequestBody(String requestId, OutputStream out) throws IOException {
        try {
            HttpTransaction tx = NetworkInspector.getTransaction(requestId);

            if (tx == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Request not found");
                error.put("id", requestId);
                sendJson(out, 404, error);
                return;
            }

            HttpResponse response = tx.getResponse();
            if (response == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No response available");
                sendJson(out, 404, error);
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("id", requestId);
            result.put("contentType", response.getContentType());
            result.put("size", response.getBodySize());

            // Check if body is stored externally
            if (response.hasExternalBody()) {
                BodyReference bodyRef = response.getBodyRef();
                BodyStorage storage = BodyStorage.getInstance();

                if (storage != null) {
                    byte[] bytes = storage.readBytes(bodyRef);
                    if (bytes != null) {
                        String contentType = response.getContentType();
                        boolean isImage = contentType != null && contentType.startsWith("image/");
                        boolean isBinary = isImage || (contentType != null && (
                            contentType.contains("octet-stream") ||
                            contentType.contains("application/pdf") ||
                            contentType.contains("audio/") ||
                            contentType.contains("video/")
                        ));

                        if (isBinary) {
                            // Return base64-encoded binary content
                            result.put("encoding", "base64");
                            result.put("body", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
                        } else {
                            // Return as text
                            result.put("encoding", "text");
                            result.put("body", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                        }
                    } else {
                        result.put("error", "Body file not found");
                    }
                } else {
                    result.put("error", "Body storage not initialized");
                }
            } else if (response.getBody() != null) {
                // Inline body
                result.put("encoding", "text");
                result.put("body", response.getBody());
            } else {
                result.put("error", "No body available");
            }

            sendJson(out, 200, result);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting network request body", e);
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

            // Also clear stored body files
            BodyStorage storage = BodyStorage.getInstance();
            if (storage != null) {
                storage.clear();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("cleared", count);
            response.put("message", "Cleared " + count + " requests");

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error clearing network requests", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * POST /network/transactions - Record an external HTTP transaction.
     *
     * <p>This endpoint allows external tools (like CDP network monitors) to record
     * HTTP transactions that were not captured by the in-app interceptors.</p>
     *
     * <p>Expected JSON format:</p>
     * <pre>{
     *   "url": "https://example.com/api",
     *   "method": "GET",
     *   "source": "CustomTab",
     *   "startTime": 1234567890,
     *   "requestHeaders": {"Content-Type": "application/json"},
     *   "requestBody": "...",
     *   "statusCode": 200,
     *   "statusMessage": "OK",
     *   "responseHeaders": {"Content-Type": "application/json"},
     *   "responseBody": "...",
     *   "duration": 150,
     *   "error": null
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private void handleNetworkRecordTransaction(String body, OutputStream out) throws IOException {
        try {
            if (body == null || body.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Request body is required");
                sendJson(out, 400, error);
                return;
            }

            Map<String, Object> data = gson.fromJson(body, Map.class);

            // Required fields
            String url = (String) data.get("url");
            if (url == null || url.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "url is required");
                sendJson(out, 400, error);
                return;
            }

            // Build request
            String method = (String) data.getOrDefault("method", "GET");
            HttpRequest.Builder requestBuilder = HttpRequest.builder()
                    .url(url)
                    .method(method);

            // Request headers
            Map<String, String> requestHeaders = (Map<String, String>) data.get("requestHeaders");
            if (requestHeaders != null) {
                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
            }

            // Pre-generate transaction ID for BodyStorage references
            String txId = UUID.randomUUID().toString();

            // Request body
            String requestBody = (String) data.get("requestBody");
            if (requestBody != null) {
                if (requestBody.length() > NetworkInspector.getMaxInlineBodySize()) {
                    BodyStorage storage = BodyStorage.getInstance();
                    if (storage != null) {
                        String reqContentType = null;
                        if (requestHeaders != null) {
                            for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
                                if ("content-type".equalsIgnoreCase(e.getKey())) {
                                    reqContentType = e.getValue();
                                    break;
                                }
                            }
                        }
                        BodyReference ref = storage.store(txId, "request", requestBody, reqContentType);
                        if (ref != null) {
                            requestBuilder.bodyRef(ref);
                        } else {
                            requestBuilder.body(requestBody);
                        }
                    } else {
                        requestBuilder.body(requestBody);
                    }
                } else {
                    requestBuilder.body(requestBody);
                }
            }

            HttpRequest request = requestBuilder.build();

            // Build transaction
            String source = (String) data.getOrDefault("source", "external");
            Number startTimeNum = (Number) data.get("startTime");
            long startTime = startTimeNum != null ? startTimeNum.longValue() : System.currentTimeMillis();

            HttpTransaction tx = HttpTransaction.create()
                    .id(txId)
                    .request(request)
                    .source(source)
                    .startTime(startTime)
                    .build();

            // Set resource type if present (for CDP/Custom Tab requests)
            String resourceType = (String) data.get("resourceType");
            if (resourceType != null) {
                tx.setResourceType(resourceType);
            }

            // Check if we have response data
            Number statusCodeNum = (Number) data.get("statusCode");
            if (statusCodeNum != null) {
                int statusCode = statusCodeNum.intValue();
                String statusMessage = (String) data.getOrDefault("statusMessage", "");

                HttpResponse.Builder responseBuilder = HttpResponse.builder()
                        .statusCode(statusCode)
                        .statusMessage(statusMessage);

                // Response headers
                Map<String, String> responseHeaders = (Map<String, String>) data.get("responseHeaders");
                if (responseHeaders != null) {
                    for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                        responseBuilder.addHeader(entry.getKey(), entry.getValue());
                    }
                }

                // Response body (may be base64 encoded for binary content)
                String responseBody = (String) data.get("responseBody");
                if (responseBody != null) {
                    Boolean isBase64 = (Boolean) data.get("responseBodyBase64");
                    if (!Boolean.TRUE.equals(isBase64)) {
                        if (responseBody.length() > NetworkInspector.getMaxInlineBodySize()) {
                            BodyStorage storage = BodyStorage.getInstance();
                            if (storage != null) {
                                String respContentType = null;
                                if (responseHeaders != null) {
                                    for (Map.Entry<String, String> e : responseHeaders.entrySet()) {
                                        if ("content-type".equalsIgnoreCase(e.getKey())) {
                                            respContentType = e.getValue();
                                            break;
                                        }
                                    }
                                }
                                BodyReference ref = storage.store(txId, "response", responseBody, respContentType);
                                if (ref != null) {
                                    responseBuilder.bodyRef(ref);
                                } else {
                                    responseBuilder.body(responseBody);
                                }
                            } else {
                                responseBuilder.body(responseBody);
                            }
                        } else {
                            responseBuilder.body(responseBody);
                        }
                    }
                    // Binary content is intentionally skipped - not useful for inspection
                }

                // Protocol
                String protocol = (String) data.get("protocol");
                if (protocol != null) {
                    responseBuilder.protocol(protocol);
                }

                tx.setResponse(responseBuilder.build());

                // Check for error
                String error = (String) data.get("error");
                if (error != null && !error.isEmpty()) {
                    tx.markFailed(error);
                } else {
                    tx.markCompleted();
                }
            } else {
                // No response yet - mark as in progress
                tx.markInProgress();
            }

            // Record the transaction
            NetworkInspector.recordTransaction(tx);

            Map<String, Object> response = new HashMap<>();
            response.put("id", tx.getId());
            response.put("message", "Transaction recorded");
            response.put("source", source);

            sendJson(out, 201, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error recording transaction", e);
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
            SidekickLog.e(TAG, "Error getting network stats", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    // =========================================================================
    // WebSocket Endpoints
    // =========================================================================

    /**
     * GET /websocket/connections - List all captured WebSocket connections.
     */
    private void handleWebSocketConnections(OutputStream out) throws IOException {
        try {
            java.util.List<WebSocketConnection> connections = WebSocketInspector.getConnections();

            java.util.List<Map<String, Object>> connectionList = new java.util.ArrayList<>();
            for (WebSocketConnection conn : connections) {
                connectionList.add(connectionToMap(conn, false));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("count", connections.size());
            response.put("connections", connectionList);

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting websocket connections", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /websocket/connections/{id} - Get a single WebSocket connection by ID.
     */
    private void handleWebSocketConnectionById(String connectionId, OutputStream out) throws IOException {
        try {
            WebSocketConnection conn = WebSocketInspector.getConnection(connectionId);

            if (conn == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Connection not found");
                error.put("id", connectionId);
                sendJson(out, 404, error);
                return;
            }

            Map<String, Object> response = connectionToMap(conn, true);
            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting websocket connection", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * DELETE /websocket/clear - Clear all captured WebSocket connections.
     */
    private void handleWebSocketClear(OutputStream out) throws IOException {
        try {
            int count = WebSocketInspector.getConnectionCount();
            WebSocketInspector.clearConnections();

            Map<String, Object> response = new HashMap<>();
            response.put("cleared", count);
            response.put("message", "Cleared " + count + " connections");

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error clearing websocket connections", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    // =========================================================================
    // Mock Endpoints
    // =========================================================================

    /**
     * GET /mock/rules - List all mock rules.
     */
    private void handleMockRulesList(OutputStream out) throws IOException {
        try {
            MockManager manager = MockManager.getInstance();
            java.util.List<MockRule> rules = manager.getRules();

            java.util.List<Map<String, Object>> ruleList = new java.util.ArrayList<>();
            for (MockRule rule : rules) {
                ruleList.add(mockRuleToMap(rule));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("count", rules.size());
            response.put("rules", ruleList);
            response.put("config", mockConfigToMap(manager.getConfig()));

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error listing mock rules", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /mock/rules/{id} - Get a single mock rule by ID.
     */
    private void handleMockRuleById(String ruleId, OutputStream out) throws IOException {
        try {
            MockManager manager = MockManager.getInstance();
            MockRule rule = manager.getRule(ruleId);

            if (rule == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Mock rule not found");
                error.put("id", ruleId);
                sendJson(out, 404, error);
                return;
            }

            sendJson(out, 200, mockRuleToMap(rule));

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting mock rule", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * POST /mock/rules - Create a new mock rule.
     */
    @SuppressWarnings("unchecked")
    private void handleCreateMockRule(String body, OutputStream out) throws IOException {
        try {
            Map<String, Object> data = gson.fromJson(body, Map.class);

            MockRule.Builder builder = MockRule.builder();

            // Required: type
            String typeStr = (String) data.get("type");
            if (typeStr == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Missing required field: type");
                sendJson(out, 400, error);
                return;
            }
            builder.type(MockType.valueOf(typeStr.toUpperCase()));

            // Optional fields
            if (data.containsKey("name")) {
                builder.name((String) data.get("name"));
            }
            if (data.containsKey("enabled")) {
                builder.enabled((Boolean) data.get("enabled"));
            }
            if (data.containsKey("urlPattern")) {
                builder.urlPattern((String) data.get("urlPattern"));
            }
            if (data.containsKey("method")) {
                builder.method((String) data.get("method"));
            }
            if (data.containsKey("direction")) {
                builder.direction(MockDirection.valueOf(((String) data.get("direction")).toUpperCase()));
            }
            if (data.containsKey("delayMs")) {
                builder.delayMs(((Number) data.get("delayMs")).intValue());
            }

            // Mock response (for HTTP)
            if (data.containsKey("mockResponse")) {
                Map<String, Object> respData = (Map<String, Object>) data.get("mockResponse");
                MockHttpResponse.Builder respBuilder = MockHttpResponse.builder();

                if (respData.containsKey("statusCode")) {
                    respBuilder.statusCode(((Number) respData.get("statusCode")).intValue());
                }
                if (respData.containsKey("statusMessage")) {
                    respBuilder.statusMessage((String) respData.get("statusMessage"));
                }
                if (respData.containsKey("body")) {
                    respBuilder.body((String) respData.get("body"));
                }
                if (respData.containsKey("contentType")) {
                    respBuilder.contentType((String) respData.get("contentType"));
                }
                if (respData.containsKey("headers")) {
                    Map<String, String> headers = (Map<String, String>) respData.get("headers");
                    respBuilder.headers(headers);
                }

                builder.mockResponse(respBuilder.build());
            }

            // Mock message (for WebSocket)
            if (data.containsKey("mockMessage")) {
                Map<String, Object> msgData = (Map<String, Object>) data.get("mockMessage");
                MockWebSocketMessage.Builder msgBuilder = MockWebSocketMessage.builder();

                if (msgData.containsKey("textPayload")) {
                    msgBuilder.textPayload((String) msgData.get("textPayload"));
                }
                if (msgData.containsKey("drop")) {
                    msgBuilder.drop((Boolean) msgData.get("drop"));
                }

                builder.mockMessage(msgBuilder.build());
            }

            MockRule rule = builder.build();
            MockManager.getInstance().addRule(rule);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rule", mockRuleToMap(rule));

            sendJson(out, 201, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error creating mock rule", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 400, error);
        }
    }

    /**
     * PUT /mock/rules/{id} - Update an existing mock rule.
     */
    @SuppressWarnings("unchecked")
    private void handleUpdateMockRule(String ruleId, String body, OutputStream out) throws IOException {
        try {
            MockManager manager = MockManager.getInstance();
            MockRule rule = manager.getRule(ruleId);

            if (rule == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Mock rule not found");
                error.put("id", ruleId);
                sendJson(out, 404, error);
                return;
            }

            Map<String, Object> data = gson.fromJson(body, Map.class);

            // Update fields
            if (data.containsKey("name")) {
                rule.setName((String) data.get("name"));
            }
            if (data.containsKey("enabled")) {
                rule.setEnabled((Boolean) data.get("enabled"));
            }
            if (data.containsKey("urlPattern")) {
                rule.setUrlPattern((String) data.get("urlPattern"));
            }
            if (data.containsKey("contentPattern")) {
                rule.setContentPattern((String) data.get("contentPattern"));
            }
            if (data.containsKey("method")) {
                rule.setMethod((String) data.get("method"));
            }
            if (data.containsKey("direction")) {
                rule.setDirection(MockDirection.valueOf(((String) data.get("direction")).toUpperCase()));
            }
            if (data.containsKey("delayMs")) {
                rule.setDelayMs(((Number) data.get("delayMs")).intValue());
            }

            // Update mock response
            if (data.containsKey("mockResponse")) {
                Map<String, Object> respData = (Map<String, Object>) data.get("mockResponse");
                MockHttpResponse.Builder respBuilder = MockHttpResponse.builder();

                if (respData.containsKey("statusCode")) {
                    respBuilder.statusCode(((Number) respData.get("statusCode")).intValue());
                }
                if (respData.containsKey("statusMessage")) {
                    respBuilder.statusMessage((String) respData.get("statusMessage"));
                }
                if (respData.containsKey("body")) {
                    respBuilder.body((String) respData.get("body"));
                }
                if (respData.containsKey("contentType")) {
                    respBuilder.contentType((String) respData.get("contentType"));
                }
                if (respData.containsKey("headers")) {
                    respBuilder.headers((Map<String, String>) respData.get("headers"));
                }

                rule.setMockResponse(respBuilder.build());
            }

            // Update mock message
            if (data.containsKey("mockMessage")) {
                Map<String, Object> msgData = (Map<String, Object>) data.get("mockMessage");
                MockWebSocketMessage.Builder msgBuilder = MockWebSocketMessage.builder();

                if (msgData.containsKey("textPayload")) {
                    msgBuilder.textPayload((String) msgData.get("textPayload"));
                }
                if (msgData.containsKey("drop")) {
                    msgBuilder.drop((Boolean) msgData.get("drop"));
                }

                rule.setMockMessage(msgBuilder.build());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rule", mockRuleToMap(rule));

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error updating mock rule", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 400, error);
        }
    }

    /**
     * DELETE /mock/rules/{id} - Delete a mock rule.
     */
    private void handleDeleteMockRule(String ruleId, OutputStream out) throws IOException {
        try {
            boolean deleted = MockManager.getInstance().deleteRule(ruleId);

            if (!deleted) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Mock rule not found");
                error.put("id", ruleId);
                sendJson(out, 404, error);
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("deleted", ruleId);

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error deleting mock rule", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * GET /mock/config - Get global mock configuration.
     */
    private void handleGetMockConfig(OutputStream out) throws IOException {
        try {
            MockConfig config = MockManager.getInstance().getConfig();
            sendJson(out, 200, mockConfigToMap(config));

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting mock config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * PUT /mock/config - Update global mock configuration.
     */
    @SuppressWarnings("unchecked")
    private void handleUpdateMockConfig(String body, OutputStream out) throws IOException {
        try {
            Map<String, Object> data = gson.fromJson(body, Map.class);
            MockConfig config = MockManager.getInstance().getConfig();

            if (data.containsKey("enabled")) {
                config.setEnabled((Boolean) data.get("enabled"));
            }
            if (data.containsKey("httpMockingEnabled")) {
                config.setHttpMockingEnabled((Boolean) data.get("httpMockingEnabled"));
            }
            if (data.containsKey("webSocketMockingEnabled")) {
                config.setWebSocketMockingEnabled((Boolean) data.get("webSocketMockingEnabled"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("config", mockConfigToMap(config));

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error updating mock config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 400, error);
        }
    }

    /**
     * POST /mock/from-request/{id} - Create a mock rule from a captured HTTP request.
     */
    private void handleCreateMockFromRequest(String requestId, OutputStream out) throws IOException {
        try {
            MockManager manager = MockManager.getInstance();
            MockRule.Builder builder = manager.createRuleFromTransaction(requestId);

            if (builder == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Request not found or has no response");
                error.put("id", requestId);
                sendJson(out, 404, error);
                return;
            }

            MockRule rule = builder.build();
            manager.addRule(rule);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rule", mockRuleToMap(rule));

            sendJson(out, 201, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error creating mock from request", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * POST /mock/from-message/{id} - Create a mock rule from a captured WebSocket message.
     */
    private void handleCreateMockFromMessage(String messageId, OutputStream out) throws IOException {
        try {
            MockManager manager = MockManager.getInstance();
            MockRule.Builder builder = manager.createRuleFromMessage(messageId);

            if (builder == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Message not found");
                error.put("id", messageId);
                sendJson(out, 404, error);
                return;
            }

            MockRule rule = builder.build();
            manager.addRule(rule);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rule", mockRuleToMap(rule));

            sendJson(out, 201, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error creating mock from message", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * Converts a MockRule to a Map for JSON serialization.
     */
    private Map<String, Object> mockRuleToMap(MockRule rule) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", rule.getId());
        map.put("name", rule.getName());
        map.put("type", rule.getType().name());
        map.put("enabled", rule.isEnabled());
        map.put("urlPattern", rule.getUrlPattern());
        map.put("contentPattern", rule.getContentPattern());
        map.put("method", rule.getMethod());
        map.put("direction", rule.getDirection() != null ? rule.getDirection().name() : null);
        map.put("delayMs", rule.getDelayMs());
        map.put("matchCount", rule.getMatchCount());
        map.put("createdAt", rule.getCreatedAt());

        // Include mock response if present
        MockHttpResponse mockResponse = rule.getMockResponse();
        if (mockResponse != null) {
            Map<String, Object> respMap = new HashMap<>();
            respMap.put("statusCode", mockResponse.getStatusCode());
            respMap.put("statusMessage", mockResponse.getStatusMessage());
            respMap.put("body", mockResponse.getBody());
            respMap.put("contentType", mockResponse.getContentType());
            respMap.put("headers", mockResponse.getHeaders());
            respMap.put("capturedTransactionId", mockResponse.getCapturedTransactionId());
            map.put("mockResponse", respMap);
        }

        // Include mock message if present
        MockWebSocketMessage mockMessage = rule.getMockMessage();
        if (mockMessage != null) {
            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("textPayload", mockMessage.getTextPayload());
            msgMap.put("drop", mockMessage.isDrop());
            msgMap.put("capturedMessageId", mockMessage.getCapturedMessageId());
            if (mockMessage.getBinaryPayload() != null) {
                msgMap.put("binaryPayload", android.util.Base64.encodeToString(
                        mockMessage.getBinaryPayload(), android.util.Base64.NO_WRAP));
            }
            map.put("mockMessage", msgMap);
        }

        return map;
    }

    /**
     * Converts a MockConfig to a Map for JSON serialization.
     */
    private Map<String, Object> mockConfigToMap(MockConfig config) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", config.isEnabled());
        map.put("httpMockingEnabled", config.isHttpMockingEnabled());
        map.put("webSocketMockingEnabled", config.isWebSocketMockingEnabled());
        map.put("httpMockingActive", config.isHttpMockingActive());
        map.put("webSocketMockingActive", config.isWebSocketMockingActive());
        return map;
    }

    // =========================================================================
    // Selection Endpoints (Multi-selection support)
    // =========================================================================

    /**
     * GET /selection/element - Get all selected UI elements.
     */
    private void handleGetSelectionElements(OutputStream out) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("count", selectedElements.size());
        response.put("elements", new ArrayList<>(selectedElements));
        sendJson(out, 200, response);
    }

    /**
     * POST /selection/element - Modify element selections.
     *
     * <p>Query param action: add (default), remove, clear</p>
     * <p>For add/remove: expects JSON body with element data.</p>
     * <p>For clear: body is ignored.</p>
     */
    @SuppressWarnings("unchecked")
    private void handleModifySelectionElements(String body, String action, OutputStream out) throws IOException {
        try {
            switch (action) {
                case "clear":
                    selectedElements.clear();
                    break;
                case "remove":
                    if (body != null && !body.trim().isEmpty() && !body.trim().equals("null")) {
                        Map<String, Object> element = gson.fromJson(body, Map.class);
                        removeElementByBounds(element);
                    }
                    break;
                case "add":
                default:
                    if (body != null && !body.trim().isEmpty() && !body.trim().equals("null")) {
                        Map<String, Object> element = gson.fromJson(body, Map.class);
                        if (!containsElementByBounds(element)) {
                            selectedElements.add(element);
                        }
                    }
                    break;
            }

            // Broadcast selection change to SSE clients
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("type", "element");
            eventData.put("action", action);
            eventData.put("count", selectedElements.size());
            eventData.put("elements", new ArrayList<>(selectedElements));
            broadcastSelectionEvent(eventData);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("count", selectedElements.size());
            response.put("elements", new ArrayList<>(selectedElements));
            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error modifying element selection", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid JSON: " + e.getMessage());
            sendJson(out, 400, error);
        }
    }

    private boolean containsElementByBounds(Map<String, Object> element) {
        Object bounds = element.get("bounds");
        if (bounds == null) return false;
        for (Map<String, Object> existing : selectedElements) {
            if (Objects.equals(existing.get("bounds"), bounds)) {
                return true;
            }
        }
        return false;
    }

    private void removeElementByBounds(Map<String, Object> element) {
        Object bounds = element.get("bounds");
        if (bounds == null) return;
        selectedElements.removeIf(existing -> Objects.equals(existing.get("bounds"), bounds));
    }

    /**
     * GET /selection/network - Get all selected network requests.
     */
    private void handleGetSelectionNetwork(OutputStream out) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("count", selectedNetworkRequests.size());
        response.put("requests", new ArrayList<>(selectedNetworkRequests));
        sendJson(out, 200, response);
    }

    /**
     * POST /selection/network - Modify network request selections.
     *
     * <p>Query param action: add (default), remove, clear</p>
     */
    @SuppressWarnings("unchecked")
    private void handleModifySelectionNetwork(String body, String action, OutputStream out) throws IOException {
        try {
            switch (action) {
                case "clear":
                    selectedNetworkRequests.clear();
                    break;
                case "remove":
                    if (body != null && !body.trim().isEmpty() && !body.trim().equals("null")) {
                        Map<String, Object> request = gson.fromJson(body, Map.class);
                        removeRequestById(request);
                    }
                    break;
                case "add":
                default:
                    if (body != null && !body.trim().isEmpty() && !body.trim().equals("null")) {
                        Map<String, Object> request = gson.fromJson(body, Map.class);
                        if (!containsRequestById(request)) {
                            selectedNetworkRequests.add(request);
                        }
                    }
                    break;
            }

            // Broadcast selection change to SSE clients
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("type", "network");
            eventData.put("action", action);
            eventData.put("count", selectedNetworkRequests.size());
            eventData.put("requests", new ArrayList<>(selectedNetworkRequests));
            broadcastSelectionEvent(eventData);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("count", selectedNetworkRequests.size());
            response.put("requests", new ArrayList<>(selectedNetworkRequests));
            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error modifying network selection", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid JSON: " + e.getMessage());
            sendJson(out, 400, error);
        }
    }

    private boolean containsRequestById(Map<String, Object> request) {
        Object id = request.get("id");
        if (id == null) return false;
        for (Map<String, Object> existing : selectedNetworkRequests) {
            if (Objects.equals(existing.get("id"), id)) {
                return true;
            }
        }
        return false;
    }

    private void removeRequestById(Map<String, Object> request) {
        Object id = request.get("id");
        if (id == null) return;
        selectedNetworkRequests.removeIf(existing -> Objects.equals(existing.get("id"), id));
    }

    /**
     * GET /selection/websocket-message - Get all selected WebSocket messages.
     */
    private void handleGetSelectionWebSocketMessage(OutputStream out) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("count", selectedWebSocketMessages.size());
        response.put("messages", new ArrayList<>(selectedWebSocketMessages));
        sendJson(out, 200, response);
    }

    /**
     * POST /selection/websocket-message - Modify WebSocket message selections.
     *
     * <p>Query param action: add (default), remove, clear</p>
     * <p>Each message selection contains: { connectionId, messageIndex, message }</p>
     */
    @SuppressWarnings("unchecked")
    private void handleModifySelectionWebSocketMessage(String body, String action, OutputStream out) throws IOException {
        try {
            switch (action) {
                case "clear":
                    selectedWebSocketMessages.clear();
                    break;
                case "remove":
                    if (body != null && !body.trim().isEmpty() && !body.trim().equals("null")) {
                        Map<String, Object> selection = gson.fromJson(body, Map.class);
                        removeWsMessageByKey(selection);
                    }
                    break;
                case "add":
                default:
                    if (body != null && !body.trim().isEmpty() && !body.trim().equals("null")) {
                        Map<String, Object> selection = gson.fromJson(body, Map.class);
                        if (!containsWsMessageByKey(selection)) {
                            selectedWebSocketMessages.add(selection);
                        }
                    }
                    break;
            }

            // Broadcast selection change to SSE clients
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("type", "websocket-message");
            eventData.put("action", action);
            eventData.put("count", selectedWebSocketMessages.size());
            eventData.put("messages", new ArrayList<>(selectedWebSocketMessages));
            broadcastSelectionEvent(eventData);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("count", selectedWebSocketMessages.size());
            response.put("messages", new ArrayList<>(selectedWebSocketMessages));
            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error modifying websocket message selection", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid JSON: " + e.getMessage());
            sendJson(out, 400, error);
        }
    }

    private boolean containsWsMessageByKey(Map<String, Object> selection) {
        Object connId = selection.get("connectionId");
        Object msgIdx = selection.get("messageIndex");
        if (connId == null || msgIdx == null) return false;
        for (Map<String, Object> existing : selectedWebSocketMessages) {
            if (Objects.equals(existing.get("connectionId"), connId) &&
                Objects.equals(existing.get("messageIndex"), msgIdx)) {
                return true;
            }
        }
        return false;
    }

    private void removeWsMessageByKey(Map<String, Object> selection) {
        Object connId = selection.get("connectionId");
        Object msgIdx = selection.get("messageIndex");
        if (connId == null || msgIdx == null) return;
        selectedWebSocketMessages.removeIf(existing ->
            Objects.equals(existing.get("connectionId"), connId) &&
            Objects.equals(existing.get("messageIndex"), msgIdx));
    }

    /**
     * Broadcasts a selection change event to all SSE clients.
     */
    private void broadcastSelectionEvent(Map<String, Object> data) {
        if (sseClients.isEmpty()) {
            return;
        }

        for (OutputStream client : sseClients) {
            try {
                sendSseEvent(client, "selection", data);
            } catch (IOException e) {
                // Client disconnected, will be cleaned up
                sseClients.remove(client);
            }
        }
    }

    /**
     * Converts a WebSocketConnection to a Map for JSON serialization.
     *
     * @param conn           the connection to convert
     * @param includeDetails whether to include messages (for detail view)
     */
    private Map<String, Object> connectionToMap(WebSocketConnection conn, boolean includeDetails) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", conn.getId());
        map.put("url", conn.getUrl());
        map.put("source", conn.getSource());
        map.put("status", conn.getStatus().name());
        map.put("connectedAt", conn.getConnectedAt());
        map.put("closedAt", conn.getClosedAt());
        map.put("closeCode", conn.getCloseCode());
        map.put("closeReason", conn.getCloseReason());
        map.put("messageCount", conn.getMessageCount());

        if (includeDetails) {
            // Include messages
            java.util.List<WebSocketMessage> messages = conn.getMessages();
            java.util.List<Map<String, Object>> messageList = new java.util.ArrayList<>();

            for (WebSocketMessage msg : messages) {
                messageList.add(messageToMap(msg));
            }

            map.put("messages", messageList);
        }

        return map;
    }

    /**
     * Converts a WebSocketMessage to a Map for JSON serialization.
     */
    private Map<String, Object> messageToMap(WebSocketMessage msg) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", msg.getId());
        map.put("connectionId", msg.getConnectionId());
        map.put("timestamp", msg.getTimestamp());
        map.put("direction", msg.getDirection().name());
        map.put("type", msg.getType().name());
        map.put("payloadSize", msg.getPayloadSize());

        // Include payload based on type
        if (msg.getType() == WebSocketMessage.MessageType.TEXT) {
            map.put("textPayload", msg.getTextPayload());
        } else if (msg.getType() == WebSocketMessage.MessageType.BINARY && msg.getBinaryPayload() != null) {
            // Convert binary to base64 for JSON
            map.put("binaryPayload", android.util.Base64.encodeToString(
                    msg.getBinaryPayload(), android.util.Base64.NO_WRAP));
        }

        // Include mock information
        map.put("mocked", msg.isMocked());
        if (msg.getMockRuleId() != null) {
            map.put("mockRuleId", msg.getMockRuleId());
        }

        return map;
    }

    // =========================================================================
    // Custom Tabs Endpoints
    // =========================================================================

    /**
     * GET /customtabs/events - List all captured Custom Tab events.
     */
    private void handleCustomTabsEvents(OutputStream out) throws IOException {
        try {
            java.util.List<CustomTabEvent> events = CustomTabsInspector.getEvents();

            java.util.List<Map<String, Object>> eventList = new java.util.ArrayList<>();
            for (CustomTabEvent event : events) {
                eventList.add(customTabEventToMap(event));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("count", events.size());
            response.put("events", eventList);

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error getting custom tab events", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * DELETE /customtabs/events - Clear all captured Custom Tab events.
     */
    private void handleCustomTabsClear(OutputStream out) throws IOException {
        try {
            int count = CustomTabsInspector.getEventCount();
            CustomTabsInspector.clearEvents();

            Map<String, Object> response = new HashMap<>();
            response.put("cleared", count);
            response.put("message", "Cleared " + count + " custom tab events");

            sendJson(out, 200, response);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error clearing custom tab events", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            sendJson(out, 500, error);
        }
    }

    /**
     * POST /customtabs/request-cdp-capture - Arms CDP capture.
     * When armed, Custom Tab launches will block until a CDP ack is received.
     */
    private void handleRequestCdpCapture(OutputStream out) throws IOException {
        cdpCaptureRequested = true;
        SidekickLog.i(TAG, "CDP capture armed");
        Map<String, Object> response = new HashMap<>();
        response.put("status", "armed");
        sendJson(out, 200, response);
    }

    /**
     * POST /customtabs/release-cdp-capture - Releases CDP capture.
     * Any pending latches are released and future launches will not block.
     */
    private void handleReleaseCdpCapture(OutputStream out) throws IOException {
        cdpCaptureRequested = false;
        // Drain pending latches one by one to avoid ConcurrentModificationException
        var iterator = pendingCdpAcks.entrySet().iterator();
        while (iterator.hasNext()) {
            iterator.next().getValue().countDown();
            iterator.remove();
        }
        SidekickLog.i(TAG, "CDP capture released");
        Map<String, Object> response = new HashMap<>();
        response.put("status", "released");
        sendJson(out, 200, response);
    }

    /**
     * POST /customtabs/ack/{eventId} - Acknowledges that CDP is attached for a given event.
     * Releases the latch so the Custom Tab launch can proceed.
     */
    private void handleCdpAck(String eventId, OutputStream out) throws IOException {
        CountDownLatch latch = pendingCdpAcks.get(eventId);
        if (latch != null) {
            latch.countDown();
            SidekickLog.i(TAG, "CDP ack received for event: " + eventId);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "acked");
            response.put("eventId", eventId);
            sendJson(out, 200, response);
        } else {
            SidekickLog.w(TAG, "CDP ack for unknown event: " + eventId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "No pending ack for event: " + eventId);
            sendJson(out, 404, error);
        }
    }

    /**
     * Converts a CustomTabEvent to a Map for JSON serialization.
     */
    private Map<String, Object> customTabEventToMap(CustomTabEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", event.getId());
        map.put("url", event.getUrl());
        map.put("headers", event.getHeaders());
        map.put("timestamp", event.getTimestamp());
        map.put("packageName", event.getPackageName());
        return map;
    }

    /**
     * Broadcasts a Custom Tab event to all connected SSE clients.
     */
    private void broadcastCustomTabEvent(CustomTabEvent event) {
        if (sseClients.isEmpty()) {
            return;
        }

        Map<String, Object> data = customTabEventToMap(event);
        data.put("eventType", "customtab_will_launch");

        for (OutputStream client : sseClients) {
            try {
                sendSseEvent(client, "customtab_will_launch", data);
            } catch (IOException e) {
                // Client disconnected, will be cleaned up
                sseClients.remove(client);
            }
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
        SidekickLog.i(TAG, "SSE client connected");

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
            SidekickLog.i(TAG, "SSE client disconnected");
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
        String json = gsonCompact.toJson(data);
        // SSE format: event: <type>\ndata: <json>\n\n (must be single-line)
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
        map.put("mocked", tx.isMocked());

        if (tx.getResourceType() != null) {
            map.put("resourceType", tx.getResourceType());
        }

        if (tx.getMockRuleId() != null) {
            map.put("mockRuleId", tx.getMockRuleId());
        }

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
