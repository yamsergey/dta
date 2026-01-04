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

            // Route request
            if (!"GET".equals(method)) {
                sendError(out, 405, "Method Not Allowed");
                return;
            }

            routeRequest(path, out);

        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
        }
    }

    /**
     * Routes the request to the appropriate handler.
     */
    private void routeRequest(String path, OutputStream out) throws IOException {
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
        response.put("version", "1.0.0");
        response.put("port", port);
        response.put("endpoints", new String[]{
                "/health",
                "/compose/hierarchy",
                "/compose/semantics"
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
