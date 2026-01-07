package io.yamsergey.adt.sidekick.network;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.yamsergey.adt.sidekick.events.EventStore;
import io.yamsergey.adt.sidekick.events.adapters.HttpEventAdapter;
import io.yamsergey.adt.sidekick.jvmti.MethodHook;

/**
 * Network Inspector for capturing and monitoring network traffic.
 *
 * <p>This inspector provides multiple ways to capture network requests:</p>
 *
 * <h3>1. OkHttp Interceptor (Recommended)</h3>
 * <pre>{@code
 * OkHttpClient client = new OkHttpClient.Builder()
 *     .addInterceptor(NetworkInspector.createOkHttpInterceptor())
 *     .build();
 * }</pre>
 *
 * <h3>2. Manual Recording</h3>
 * <pre>{@code
 * NetworkRequest request = NetworkInspector.startRequest("https://api.example.com", "GET");
 * // ... perform request ...
 * request.setResponseCode(200).setResponseBody(body).markCompleted();
 * }</pre>
 *
 * <h3>3. Custom Hooks (Advanced)</h3>
 * <pre>{@code
 * NetworkInspector.registerHook(new MethodHook() {
 *     public String getTargetClass() { return "my.custom.HttpClient"; }
 *     public String getTargetMethod() { return "execute"; }
 *     // ...
 * });
 * }</pre>
 */
public final class NetworkInspector {

    private static final String TAG = "NetworkInspector";
    private static final int MAX_REQUESTS = 1000;
    private static final int MAX_BODY_SIZE = 512 * 1024; // 512 KB

    // Storage
    private static final List<NetworkRequest> requests = new CopyOnWriteArrayList<>();
    private static final Map<String, NetworkRequest> requestsById = new ConcurrentHashMap<>();
    private static final List<MethodHook> customHooks = new CopyOnWriteArrayList<>();

    // Listeners
    private static final List<NetworkListener> listeners = new CopyOnWriteArrayList<>();

    // Settings
    private static volatile boolean enabled = true;
    private static volatile boolean captureRequestBody = true;
    private static volatile boolean captureResponseBody = true;
    private static volatile int maxBodySize = MAX_BODY_SIZE;

    // Prevent instantiation
    private NetworkInspector() {}

    // =========================================================================
    // Request Management
    // =========================================================================

    /**
     * Starts tracking a new network request.
     *
     * @param url    the request URL
     * @param method the HTTP method (GET, POST, etc.)
     * @return the new request object for further updates
     */
    public static NetworkRequest startRequest(String url, String method) {
        if (!enabled) {
            return new NetworkRequest(url, method); // Return dummy
        }

        NetworkRequest request = new NetworkRequest(url, method);
        request.markInProgress();

        addRequest(request);

        Log.d(TAG, "Started request: " + request.getId() + " " + method + " " + url);
        notifyRequestStarted(request);

        return request;
    }

    /**
     * Records a completed request.
     *
     * @param request the request to record
     */
    public static void recordRequest(NetworkRequest request) {
        if (!enabled || request == null) {
            return;
        }

        addRequest(request);
        notifyRequestCompleted(request);
    }

    /**
     * Gets a request by ID.
     *
     * @param id the request ID
     * @return the request, or null if not found
     */
    public static NetworkRequest getRequest(String id) {
        return requestsById.get(id);
    }

    /**
     * Gets all captured requests.
     *
     * @return unmodifiable list of requests (newest first)
     */
    public static List<NetworkRequest> getRequests() {
        List<NetworkRequest> result = new ArrayList<>(requests);
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    /**
     * Gets requests filtered by status.
     *
     * @param status the status to filter by
     * @return list of matching requests
     */
    public static List<NetworkRequest> getRequestsByStatus(NetworkRequest.Status status) {
        List<NetworkRequest> result = new ArrayList<>();
        for (NetworkRequest request : requests) {
            if (request.getStatus() == status) {
                result.add(request);
            }
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * Clears all captured requests.
     */
    public static void clearRequests() {
        requests.clear();
        requestsById.clear();
        Log.d(TAG, "Cleared all requests");
    }

    /**
     * Gets the count of captured requests.
     */
    public static int getRequestCount() {
        return requests.size();
    }

    // =========================================================================
    // Hook Management
    // =========================================================================

    /**
     * Registers a custom hook for intercepting network calls.
     *
     * <p>Custom hooks can be used to intercept calls to libraries not
     * supported by the built-in interceptors.</p>
     *
     * @param hook the hook to register
     */
    public static void registerHook(MethodHook hook) {
        customHooks.add(hook);
        Log.d(TAG, "Registered custom hook: " + hook.getTargetClass() + "." + hook.getTargetMethod());
    }

    /**
     * Gets all registered custom hooks.
     */
    public static List<MethodHook> getCustomHooks() {
        return Collections.unmodifiableList(customHooks);
    }

    // =========================================================================
    // OkHttp Integration
    // =========================================================================

    /**
     * Creates an OkHttp interceptor for network inspection.
     *
     * <p>Add this interceptor to your OkHttpClient:</p>
     * <pre>{@code
     * OkHttpClient client = new OkHttpClient.Builder()
     *     .addInterceptor(NetworkInspector.createOkHttpInterceptor())
     *     .build();
     * }</pre>
     *
     * @return an OkHttp Interceptor instance, or null if OkHttp is not available
     */
    public static Object createOkHttpInterceptor() {
        try {
            // Check if OkHttp is available
            Class.forName("okhttp3.Interceptor");
            return new OkHttpNetworkInterceptor();
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "OkHttp not available");
            return null;
        }
    }

    // =========================================================================
    // Listeners
    // =========================================================================

    /**
     * Adds a listener for network events.
     */
    public static void addListener(NetworkListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a network listener.
     */
    public static void removeListener(NetworkListener listener) {
        listeners.remove(listener);
    }

    // =========================================================================
    // Settings
    // =========================================================================

    /**
     * Enables or disables network inspection.
     */
    public static void setEnabled(boolean enabled) {
        NetworkInspector.enabled = enabled;
    }

    /**
     * Returns whether network inspection is enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether to capture request bodies.
     */
    public static void setCaptureRequestBody(boolean capture) {
        captureRequestBody = capture;
    }

    /**
     * Returns whether request bodies are captured.
     */
    public static boolean isCaptureRequestBody() {
        return captureRequestBody;
    }

    /**
     * Sets whether to capture response bodies.
     */
    public static void setCaptureResponseBody(boolean capture) {
        captureResponseBody = capture;
    }

    /**
     * Returns whether response bodies are captured.
     */
    public static boolean isCaptureResponseBody() {
        return captureResponseBody;
    }

    /**
     * Sets the maximum body size to capture (in bytes).
     */
    public static void setMaxBodySize(int size) {
        maxBodySize = size;
    }

    /**
     * Gets the maximum body size to capture.
     */
    public static int getMaxBodySize() {
        return maxBodySize;
    }

    // =========================================================================
    // Internal Methods
    // =========================================================================

    private static void addRequest(NetworkRequest request) {
        // Limit the number of stored requests
        while (requests.size() >= MAX_REQUESTS) {
            NetworkRequest oldest = requests.remove(0);
            requestsById.remove(oldest.getId());
        }

        requests.add(request);
        requestsById.put(request.getId(), request);
    }

    private static void notifyRequestStarted(NetworkRequest request) {
        for (NetworkListener listener : listeners) {
            try {
                listener.onRequestStarted(request);
            } catch (Exception e) {
                Log.e(TAG, "Error in listener", e);
            }
        }

        // Workaround: Record to EventStore on request start since onExit injection
        // is not yet working. Events will show IN_PROGRESS status without response data.
        recordToEventStore(request);
    }

    /**
     * Called when a request is marked as completed.
     * This triggers listeners and records to EventStore.
     */
    static void onRequestCompleted(NetworkRequest request) {
        if (!enabled || request == null) {
            return;
        }

        for (NetworkListener listener : listeners) {
            try {
                listener.onRequestCompleted(request);
            } catch (Exception e) {
                Log.e(TAG, "Error in listener", e);
            }
        }

        // Record to binary EventStore
        recordToEventStore(request);
    }

    private static void notifyRequestCompleted(NetworkRequest request) {
        onRequestCompleted(request);
    }

    /**
     * Records a completed request to the binary EventStore.
     */
    private static void recordToEventStore(NetworkRequest request) {
        try {
            EventStore store = EventStore.getInstanceOrNull();
            if (store != null) {
                store.record(HttpEventAdapter.getInstance(), request);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to record to EventStore", e);
        }
    }

    /**
     * Listener interface for network events.
     */
    public interface NetworkListener {
        void onRequestStarted(NetworkRequest request);
        void onRequestCompleted(NetworkRequest request);
    }
}
