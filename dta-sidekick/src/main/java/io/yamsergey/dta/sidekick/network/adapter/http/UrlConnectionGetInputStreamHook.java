package io.yamsergey.dta.sidekick.network.adapter.http;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.HttpRequest;
import io.yamsergey.dta.sidekick.network.HttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;
import io.yamsergey.dta.sidekick.network.NetworkInspector;

/**
 * JVMTI hook for intercepting HttpURLConnection.getInputStream().
 *
 * <p>This hook captures HTTP requests made via URLConnection when the caller
 * reads the response stream. It extracts request/response details and records
 * them with NetworkInspector.</p>
 *
 * <p>Note: Unlike OkHttp which has a clear request/response flow, URLConnection
 * is more complex - the connection can be established lazily, and request
 * properties are set before connecting.</p>
 */
public class UrlConnectionGetInputStreamHook implements MethodHook {

    private static final String TAG = "UrlConnectionHook";

    // Default target: Android's bundled OkHttp HTTP impl (covers http:// URLs).
    // For https:// URLs the JVM dispatches via HttpsURLConnectionImpl →
    // DelegatingHttpsURLConnection.getInputStream() → delegate.getInputStream(),
    // and ART's optimization of the final delegate call doesn't reliably trigger
    // the bytecode-level hook on HttpURLConnectionImpl. So we register a
    // separate hook (this same class with a different target) on the delegating
    // class to catch HTTPS — see UrlConnectionAdapter.getHooks().
    private final String targetClass;

    public UrlConnectionGetInputStreamHook() {
        this("com.android.okhttp.internal.huc.HttpURLConnectionImpl");
    }

    public UrlConnectionGetInputStreamHook(String targetClass) {
        this.targetClass = targetClass;
    }

    @Override
    public String getTargetClass() {
        return targetClass;
    }

    @Override
    public String getTargetMethod() {
        return "getInputStream";
    }

    @Override
    public String getMethodSignature() {
        return "()Ljava/io/InputStream;";
    }

    @Override
    public String getId() {
        // Distinguish hooks by target class so multiple instances don't collide
        // in the JvmtiAgent's HookRegistry.
        String shortName = targetClass.substring(targetClass.lastIndexOf('.') + 1);
        return "urlconnection-getinputstream-" + shortName;
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (!(thisObj instanceof HttpURLConnection)) {
                return;
            }

            HttpURLConnection conn = (HttpURLConnection) thisObj;

            // Build request from connection properties
            URL url = conn.getURL();
            String method = conn.getRequestMethod();

            HttpRequest.Builder requestBuilder = HttpRequest.builder()
                    .url(url.toString())
                    .method(method);

            // Capture request headers
            Map<String, List<String>> requestHeaders = conn.getRequestProperties();
            for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
                String name = entry.getKey();
                if (name != null) {
                    for (String value : entry.getValue()) {
                        requestBuilder.addHeader(name, value);
                    }
                }
            }

            HttpRequest request = requestBuilder.build();

            // Start transaction
            HttpTransaction tx = NetworkInspector.startTransaction(request, "URLConnection");
            SidekickLog.i(TAG, ">>> " + method + " " + url);

            // Store transaction ID for onExit
            storeTransactionId(tx.getId());

        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        try {
            String txId = getStoredTransactionId();
            if (txId == null) {
                return result;
            }

            HttpTransaction tx = NetworkInspector.getTransaction(txId);
            if (tx == null) {
                return result;
            }

            if (!(thisObj instanceof HttpURLConnection)) {
                return result;
            }

            HttpURLConnection conn = (HttpURLConnection) thisObj;

            // Build response
            HttpResponse.Builder responseBuilder = HttpResponse.builder()
                    .statusCode(conn.getResponseCode())
                    .statusMessage(conn.getResponseMessage());

            // Capture response headers
            Map<String, List<String>> headers = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String name = entry.getKey();
                if (name != null) {  // null key is the status line
                    for (String value : entry.getValue()) {
                        responseBuilder.addHeader(name, value);
                    }
                }
            }

            // Get content type and length
            String contentType = conn.getContentType();
            if (contentType != null) {
                responseBuilder.contentType(contentType);
            }

            int contentLength = conn.getContentLength();
            if (contentLength >= 0) {
                responseBuilder.bodySize(contentLength);
            }

            // Note: We don't capture the body here because:
            // 1. The stream hasn't been read yet
            // 2. Reading it would consume the data before the caller can use it
            // A more sophisticated approach would wrap the InputStream
            // TODO: Consider wrapping InputStream to capture body as it's read

            tx.setResponse(responseBuilder.build());
            SidekickLog.i(TAG, "<<< " + tx.getResponseCode() + " " + tx.getResponse().getStatusMessage());

            tx.markCompleted();
            NetworkInspector.onTransactionCompleted(tx);

        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onExit", t);
        }
        return result;
    }

    @Override
    public Throwable onException(Object thisObj, Throwable throwable) {
        try {
            String txId = getStoredTransactionId();
            if (txId != null) {
                HttpTransaction tx = NetworkInspector.getTransaction(txId);
                if (tx != null) {
                    tx.markFailed(throwable.getMessage());
                    NetworkInspector.onTransactionCompleted(tx);
                    SidekickLog.e(TAG, "!!! Request failed: " + txId + " - " + throwable.getMessage());
                }
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onException", t);
        }
        return throwable;
    }

    // =========================================================================
    // Transaction ID Storage (ThreadLocal)
    // =========================================================================

    private static final ThreadLocal<String> transactionIdHolder = new ThreadLocal<>();

    private void storeTransactionId(String id) {
        transactionIdHolder.set(id);
    }

    private String getStoredTransactionId() {
        String id = transactionIdHolder.get();
        transactionIdHolder.remove();
        return id;
    }
}
