package io.yamsergey.dta.sidekick.network;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.yamsergey.dta.sidekick.events.EventStore;
import io.yamsergey.dta.sidekick.events.adapters.HttpEventAdapter;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;

/**
 * Network Inspector for capturing and monitoring HTTP traffic.
 *
 * <p>This inspector captures HTTP transactions using {@link HttpTransaction} objects
 * which contain complete request and response details.</p>
 *
 * <h3>1. JVMTI Hooks (Automatic)</h3>
 * <p>When dta-sidekick is included as a dependency, OkHttp requests are
 * automatically intercepted via JVMTI bytecode transformation.</p>
 *
 * <h3>2. OkHttp Interceptor (Manual)</h3>
 * <pre>{@code
 * OkHttpClient client = new OkHttpClient.Builder()
 *     .addInterceptor(NetworkInspector.createOkHttpInterceptor())
 *     .build();
 * }</pre>
 *
 * <h3>3. Manual Recording</h3>
 * <pre>{@code
 * HttpTransaction tx = NetworkInspector.startTransaction(
 *     HttpRequest.builder()
 *         .url("https://api.example.com")
 *         .method("GET")
 *         .build());
 *
 * // ... perform request ...
 *
 * tx.setResponse(HttpResponse.builder()
 *     .statusCode(200)
 *     .body(responseBody)
 *     .build());
 * tx.markCompleted();
 * NetworkInspector.onTransactionCompleted(tx);
 * }</pre>
 */
public final class NetworkInspector {

    private static final String TAG = "NetworkInspector";
    private static final int MAX_TRANSACTIONS = 1000;
    private static final int DEFAULT_MAX_BODY_SIZE = 512 * 1024; // 512 KB

    // Storage
    private static final List<HttpTransaction> transactions = new CopyOnWriteArrayList<>();
    private static final Map<String, HttpTransaction> transactionsById = new ConcurrentHashMap<>();
    private static final List<MethodHook> customHooks = new CopyOnWriteArrayList<>();

    // Listeners
    private static final List<TransactionListener> listeners = new CopyOnWriteArrayList<>();

    // Settings
    private static volatile boolean enabled = true;
    private static volatile boolean captureRequestBody = true;
    private static volatile boolean captureResponseBody = true;
    private static volatile int maxInlineBodySize = DEFAULT_MAX_BODY_SIZE;

    // Prevent instantiation
    private NetworkInspector() {}

    // =========================================================================
    // Transaction Management
    // =========================================================================

    /**
     * Starts tracking a new HTTP transaction.
     *
     * @param request the HTTP request
     * @return the new transaction object for further updates
     */
    public static HttpTransaction startTransaction(HttpRequest request) {
        return startTransaction(request, null);
    }

    /**
     * Starts tracking a new HTTP transaction with a source identifier.
     *
     * @param request the HTTP request
     * @param source  the source library (e.g., "OkHttp", "HttpURLConnection")
     * @return the new transaction object for further updates
     */
    public static HttpTransaction startTransaction(HttpRequest request, String source) {
        if (!enabled) {
            // Return a transaction that won't be tracked
            return HttpTransaction.create()
                    .request(request)
                    .source(source)
                    .build();
        }

        HttpTransaction tx = HttpTransaction.create()
                .request(request)
                .source(source)
                .build();
        tx.markInProgress();

        addTransaction(tx);

        SidekickLog.d(TAG, "Started transaction: " + tx.getId() + " " + request.getMethod() + " " + request.getUrl());
        notifyTransactionStarted(tx);

        return tx;
    }

    /**
     * Records a completed transaction.
     *
     * @param transaction the transaction to record
     */
    public static void recordTransaction(HttpTransaction transaction) {
        if (!enabled || transaction == null) {
            return;
        }

        addTransaction(transaction);
        notifyTransactionCompleted(transaction);
    }

    /**
     * Gets a transaction by ID.
     *
     * @param id the transaction ID
     * @return the transaction, or null if not found
     */
    public static HttpTransaction getTransaction(String id) {
        return transactionsById.get(id);
    }

    /**
     * Gets all captured transactions.
     *
     * @return unmodifiable list of transactions (newest first)
     */
    public static List<HttpTransaction> getTransactions() {
        List<HttpTransaction> result = new ArrayList<>(transactions);
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    /**
     * Gets transactions filtered by status.
     *
     * @param status the status to filter by
     * @return list of matching transactions
     */
    public static List<HttpTransaction> getTransactionsByStatus(HttpTransaction.Status status) {
        List<HttpTransaction> result = new ArrayList<>();
        for (HttpTransaction tx : transactions) {
            if (tx.getStatus() == status) {
                result.add(tx);
            }
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * Clears all captured transactions.
     */
    public static void clearTransactions() {
        transactions.clear();
        transactionsById.clear();
        SidekickLog.d(TAG, "Cleared all transactions");
    }

    /**
     * Gets the count of captured transactions.
     */
    public static int getTransactionCount() {
        return transactions.size();
    }

    // =========================================================================
    // Hook Management
    // =========================================================================

    /**
     * Registers a custom hook for intercepting network calls.
     *
     * @param hook the hook to register
     */
    public static void registerHook(MethodHook hook) {
        customHooks.add(hook);
        SidekickLog.d(TAG, "Registered custom hook: " + hook.getTargetClass() + "." + hook.getTargetMethod());
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
     * @return an OkHttp Interceptor instance, or null if OkHttp is not available
     */
    public static Object createOkHttpInterceptor() {
        try {
            Class.forName("okhttp3.Interceptor");
            return new OkHttpNetworkInterceptor();
        } catch (ClassNotFoundException e) {
            SidekickLog.w(TAG, "OkHttp not available");
            return null;
        }
    }

    // =========================================================================
    // Listeners
    // =========================================================================

    /**
     * Adds a listener for transaction events.
     */
    public static void addListener(TransactionListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a transaction listener.
     */
    public static void removeListener(TransactionListener listener) {
        listeners.remove(listener);
    }

    // =========================================================================
    // Settings
    // =========================================================================

    public static void setEnabled(boolean enabled) {
        NetworkInspector.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setCaptureRequestBody(boolean capture) {
        captureRequestBody = capture;
    }

    public static boolean isCaptureRequestBody() {
        return captureRequestBody;
    }

    public static void setCaptureResponseBody(boolean capture) {
        captureResponseBody = capture;
    }

    public static boolean isCaptureResponseBody() {
        return captureResponseBody;
    }

    /**
     * Sets the maximum body size to store inline (in bytes).
     * Bodies larger than this are stored to disk with a reference.
     */
    public static void setMaxInlineBodySize(int size) {
        maxInlineBodySize = size;
    }

    public static int getMaxInlineBodySize() {
        return maxInlineBodySize;
    }

    // =========================================================================
    // Internal Methods
    // =========================================================================

    private static void addTransaction(HttpTransaction tx) {
        // Update existing transaction if same ID (e.g., CDP pending → completed)
        HttpTransaction existing = transactionsById.get(tx.getId());
        if (existing != null) {
            int idx = transactions.indexOf(existing);
            if (idx >= 0) {
                transactions.set(idx, tx);
            }
            transactionsById.put(tx.getId(), tx);
            return;
        }

        // Limit the number of stored transactions
        while (transactions.size() >= MAX_TRANSACTIONS) {
            HttpTransaction oldest = transactions.remove(0);
            transactionsById.remove(oldest.getId());
        }

        transactions.add(tx);
        transactionsById.put(tx.getId(), tx);
    }

    private static void notifyTransactionStarted(HttpTransaction tx) {
        for (TransactionListener listener : listeners) {
            try {
                listener.onTransactionStarted(tx);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }
        // Record to EventStore (server will merge with completion event)
        recordToEventStore(tx);
    }

    /**
     * Called when a transaction is marked as completed.
     * This triggers listeners and records to EventStore.
     */
    public static void onTransactionCompleted(HttpTransaction tx) {
        if (!enabled || tx == null) {
            return;
        }

        for (TransactionListener listener : listeners) {
            try {
                listener.onTransactionCompleted(tx);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }

        // Record to binary EventStore
        recordToEventStore(tx);
    }

    private static void notifyTransactionCompleted(HttpTransaction tx) {
        onTransactionCompleted(tx);
    }

    /**
     * Records a transaction to the binary EventStore.
     */
    private static void recordToEventStore(HttpTransaction tx) {
        try {
            EventStore store = EventStore.getInstanceOrNull();
            if (store != null) {
                store.record(HttpEventAdapter.getInstance(), tx);
            }
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to record to EventStore", e);
        }
    }

    /**
     * Listener interface for transaction events.
     */
    public interface TransactionListener {
        void onTransactionStarted(HttpTransaction transaction);
        void onTransactionCompleted(HttpTransaction transaction);
    }

    // =========================================================================
    // Legacy Compatibility (deprecated)
    // =========================================================================

    /**
     * @deprecated Use {@link #startTransaction(HttpRequest)} instead
     */
    @Deprecated
    public static NetworkRequest startRequest(String url, String method) {
        // Create legacy NetworkRequest for backwards compatibility
        NetworkRequest request = new NetworkRequest(url, method);
        request.markInProgress();
        return request;
    }

    /**
     * @deprecated Use {@link #getTransaction(String)} instead
     */
    @Deprecated
    public static NetworkRequest getRequest(String id) {
        // Legacy support - try to find in old storage
        return null;
    }
}
