package io.yamsergey.adt.sidekick.network.adapter.http;

import android.util.Log;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.BodyReference;
import io.yamsergey.adt.sidekick.network.BodyStorage;
import io.yamsergey.adt.sidekick.network.HttpRequest;
import io.yamsergey.adt.sidekick.network.HttpResponse;
import io.yamsergey.adt.sidekick.network.HttpTransaction;
import io.yamsergey.adt.sidekick.network.NetworkInspector;

/**
 * JVMTI hook for intercepting OkHttp network calls.
 *
 * <p>This hook intercepts calls to RealCall.execute() and captures complete
 * request/response details including headers, bodies, and timing.</p>
 *
 * <p>Large bodies (over the configured threshold) are automatically stored
 * to disk and replaced with a {@link BodyReference}.</p>
 */
public class OkHttpExecuteHook implements MethodHook {

    private static final String TAG = "OkHttpHook";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.connection.RealCall";
    }

    @Override
    public String getTargetMethod() {
        return "execute";
    }

    @Override
    public String getMethodSignature() {
        return "()Lokhttp3/Response;";
    }

    @Override
    public String getId() {
        return "okhttp-execute";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            Object okRequest = getOkHttpRequest(thisObj);
            if (okRequest == null) {
                return;
            }

            // Build HttpRequest with full details
            HttpRequest.Builder requestBuilder = HttpRequest.builder()
                    .url(getUrl(okRequest))
                    .method(getMethod(okRequest));

            // Capture headers
            captureRequestHeaders(okRequest, requestBuilder);

            // Capture body if enabled
            if (NetworkInspector.isCaptureRequestBody()) {
                captureRequestBody(okRequest, requestBuilder);
            }

            HttpRequest request = requestBuilder.build();

            // Start transaction
            HttpTransaction tx = NetworkInspector.startTransaction(request, "OkHttp");
            Log.i(TAG, ">>> " + request.getMethod() + " " + request.getUrl());

            // Store transaction ID for onExit
            storeTransactionId(tx.getId());

        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
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

            // Build HttpResponse with full details
            if (result != null) {
                HttpResponse.Builder responseBuilder = HttpResponse.builder()
                        .statusCode(getResponseCode(result))
                        .statusMessage(getResponseMessage(result))
                        .protocol(getProtocol(result));

                // Capture headers
                captureResponseHeaders(result, responseBuilder);

                // Capture body if enabled
                if (NetworkInspector.isCaptureResponseBody()) {
                    captureResponseBody(result, tx.getId(), responseBuilder);
                }

                tx.setResponse(responseBuilder.build());
                Log.i(TAG, "<<< " + tx.getResponseCode() + " " + tx.getResponse().getStatusMessage());
            }

            tx.markCompleted();
            NetworkInspector.onTransactionCompleted(tx);
            Log.d(TAG, "Transaction completed: " + txId + " (duration=" + tx.getDuration() + "ms)");

        } catch (Throwable t) {
            Log.e(TAG, "Error in onExit", t);
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
                    Log.e(TAG, "!!! Request failed: " + txId + " - " + throwable.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onException", t);
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

    // =========================================================================
    // Request Capture
    // =========================================================================

    private void captureRequestHeaders(Object okRequest, HttpRequest.Builder builder) {
        try {
            Object headers = invoke(okRequest, "headers");
            if (headers == null) return;

            int size = (int) invoke(headers, "size");
            for (int i = 0; i < size; i++) {
                String name = (String) invoke(headers, "name", new Class[]{int.class}, i);
                String value = (String) invoke(headers, "value", new Class[]{int.class}, i);
                builder.addHeader(name, value);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture request headers", e);
        }
    }

    private void captureRequestBody(Object okRequest, HttpRequest.Builder builder) {
        try {
            Object body = invoke(okRequest, "body");
            if (body == null) return;

            // Get content type
            Object mediaType = invoke(body, "contentType");
            if (mediaType != null) {
                builder.contentType(mediaType.toString());
            }

            // Get content length
            long contentLength = (long) invoke(body, "contentLength");
            builder.bodySize(contentLength);

            // Only capture text bodies within size limit
            if (!isTextContent(mediaType)) {
                return;
            }

            int maxSize = NetworkInspector.getMaxInlineBodySize();
            if (contentLength > 0 && contentLength <= maxSize) {
                // Read body using Buffer
                Object buffer = createBuffer();
                invoke(body, "writeTo", new Class[]{findClass("okio.BufferedSink")}, buffer);
                String bodyContent = (String) invoke(buffer, "readString",
                        new Class[]{Charset.class}, UTF8);
                builder.body(bodyContent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture request body", e);
        }
    }

    // =========================================================================
    // Response Capture
    // =========================================================================

    private void captureResponseHeaders(Object okResponse, HttpResponse.Builder builder) {
        try {
            Object headers = invoke(okResponse, "headers");
            if (headers == null) return;

            int size = (int) invoke(headers, "size");
            for (int i = 0; i < size; i++) {
                String name = (String) invoke(headers, "name", new Class[]{int.class}, i);
                String value = (String) invoke(headers, "value", new Class[]{int.class}, i);
                builder.addHeader(name, value);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture response headers", e);
        }
    }

    private void captureResponseBody(Object okResponse, String txId, HttpResponse.Builder builder) {
        try {
            Object body = invoke(okResponse, "body");
            if (body == null) return;

            // Get content type
            Object mediaType = invoke(body, "contentType");
            if (mediaType != null) {
                builder.contentType(mediaType.toString());
            }

            // Get content length
            long contentLength = (long) invoke(body, "contentLength");
            builder.bodySize(contentLength);

            // Only capture text bodies
            if (!isTextContent(mediaType)) {
                return;
            }

            // Get buffered source and read body
            Object source = invoke(body, "source");
            if (source == null) return;

            // Request full body
            invoke(source, "request", new Class[]{long.class}, Long.MAX_VALUE);

            // Get buffer
            Object buffer = invoke(source, "getBuffer");
            if (buffer == null) return;

            long size = (long) invoke(buffer, "size");
            builder.bodySize(size);

            int maxInlineSize = NetworkInspector.getMaxInlineBodySize();

            if (size <= maxInlineSize) {
                // Store inline
                Object clonedBuffer = invoke(buffer, "clone");
                String bodyContent = (String) invoke(clonedBuffer, "readString",
                        new Class[]{Charset.class}, UTF8);
                builder.body(bodyContent);
            } else {
                // Store to disk with reference
                Object clonedBuffer = invoke(buffer, "clone");
                String bodyContent = (String) invoke(clonedBuffer, "readString",
                        new Class[]{Charset.class}, UTF8);

                BodyStorage storage = BodyStorage.getInstance();
                if (storage != null) {
                    BodyReference ref = storage.store(txId, "response", bodyContent,
                            mediaType != null ? mediaType.toString() : null);
                    if (ref != null) {
                        builder.bodyRef(ref);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture response body", e);
        }
    }

    // =========================================================================
    // OkHttp Reflection Helpers
    // =========================================================================

    private Object getOkHttpRequest(Object call) {
        try {
            return invoke(call, "request");
        } catch (Exception e) {
            try {
                java.lang.reflect.Field f = call.getClass().getDeclaredField("originalRequest");
                f.setAccessible(true);
                return f.get(call);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private String getUrl(Object request) {
        try {
            Object url = invoke(request, "url");
            return url != null ? url.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getMethod(Object request) {
        try {
            return (String) invoke(request, "method");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private int getResponseCode(Object response) {
        try {
            return (int) invoke(response, "code");
        } catch (Exception e) {
            return -1;
        }
    }

    private String getResponseMessage(Object response) {
        try {
            return (String) invoke(response, "message");
        } catch (Exception e) {
            return null;
        }
    }

    private String getProtocol(Object response) {
        try {
            Object protocol = invoke(response, "protocol");
            return protocol != null ? protocol.toString() : "HTTP/1.1";
        } catch (Exception e) {
            return "HTTP/1.1";
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    private boolean isTextContent(Object mediaType) {
        if (mediaType == null) return false;

        String type = mediaType.toString().toLowerCase();
        return type.contains("text/") ||
                type.contains("json") ||
                type.contains("xml") ||
                type.contains("javascript") ||
                type.contains("x-www-form-urlencoded");
    }

    private Object createBuffer() throws Exception {
        Class<?> bufferClass = findClass("okio.Buffer");
        return bufferClass.newInstance();
    }

    private Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Object invoke(Object obj, String methodName) throws Exception {
        Method method = obj.getClass().getMethod(methodName);
        return method.invoke(obj);
    }

    private Object invoke(Object obj, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = obj.getClass().getMethod(methodName, paramTypes);
        return method.invoke(obj, args);
    }
}
