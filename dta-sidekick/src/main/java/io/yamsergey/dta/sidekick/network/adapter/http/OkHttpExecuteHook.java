package io.yamsergey.dta.sidekick.network.adapter.http;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.mock.MockManager;
import io.yamsergey.dta.sidekick.mock.MockRule;
import io.yamsergey.dta.sidekick.mock.OkHttpResponseBuilder;
import io.yamsergey.dta.sidekick.network.BodyReference;
import io.yamsergey.dta.sidekick.network.BodyStorage;
import io.yamsergey.dta.sidekick.network.HttpRequest;
import io.yamsergey.dta.sidekick.network.HttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;
import io.yamsergey.dta.sidekick.network.NetworkInspector;

/**
 * JVMTI hook for intercepting OkHttp network calls.
 *
 * <p>This hook intercepts calls to RealCall.getResponseWithInterceptorChain()
 * which is the internal method called by both synchronous execute() and
 * asynchronous enqueue() code paths, ensuring all HTTP requests are captured.</p>
 *
 * <p>Supports both OkHttp 3.x (Java) and OkHttp 4.x (Kotlin):
 * <ul>
 *   <li>OkHttp 3.x: {@code getResponseWithInterceptorChain()}</li>
 *   <li>OkHttp 4.x: {@code getResponseWithInterceptorChain$okhttp()} (Kotlin internal mangling)</li>
 * </ul>
 *
 * <p>Large bodies (over the configured threshold) are automatically stored
 * to disk and replaced with a {@link BodyReference}.</p>
 */
public class OkHttpExecuteHook implements MethodHook {

    private static final String TAG = "OkHttpHook";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private final String methodName;
    private final String hookId;

    /**
     * Creates a hook for the specified method name.
     *
     * @param methodName the target method name (varies by OkHttp version)
     * @param hookId unique identifier for this hook variant
     */
    public OkHttpExecuteHook(String methodName, String hookId) {
        this.methodName = methodName;
        this.hookId = hookId;
    }

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.connection.RealCall";
    }

    @Override
    public String getTargetMethod() {
        // Hook getResponseWithInterceptorChain() - the internal method called by both
        // sync (execute) and async (enqueue) paths.
        // Method name varies: OkHttp 3.x uses plain name, OkHttp 4.x adds $okhttp suffix
        return methodName;
    }

    @Override
    public String getMethodSignature() {
        return "()Lokhttp3/Response;";
    }

    @Override
    public String getId() {
        return "okhttp-interceptor-chain-" + hookId;
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
            SidekickLog.i(TAG, ">>> " + request.getMethod() + " " + request.getUrl());

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

            // Check for mock rules BEFORE processing the real response
            MockManager mockManager = MockManager.getInstance();
            MockRule matchingRule = mockManager.findMatchingHttpRule(tx.getRequest());

            if (matchingRule != null && matchingRule.getMockResponse() != null) {
                // Apply mock response
                SidekickLog.i(TAG, "<<< MOCK " + matchingRule.getMockResponse().getStatusCode() +
                        " " + matchingRule.getMockResponse().getStatusMessage() +
                        " (rule: " + matchingRule.getName() + ")");

                // Apply optional delay
                if (matchingRule.getDelayMs() > 0) {
                    try {
                        Thread.sleep(matchingRule.getDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // Build mock OkHttp Response
                Object mockResponse = OkHttpResponseBuilder.build(thisObj, result, matchingRule.getMockResponse());
                if (mockResponse != null) {
                    // Update transaction with mock response info
                    var mockData = matchingRule.getMockResponse();
                    HttpResponse.Builder responseBuilder = HttpResponse.builder()
                            .statusCode(mockData.getStatusCode())
                            .statusMessage(mockData.getStatusMessage())
                            .contentType(mockData.getContentType())
                            .body(mockData.getBody());

                    // Add mock headers
                    if (mockData.getHeaders() != null) {
                        for (var entry : mockData.getHeaders().entrySet()) {
                            responseBuilder.addHeader(entry.getKey(), entry.getValue());
                        }
                    }

                    tx.setResponse(responseBuilder.build());
                    tx.setMocked(true);
                    tx.setMockRuleId(matchingRule.getId());
                    matchingRule.incrementMatchCount();

                    tx.markCompleted();
                    NetworkInspector.onTransactionCompleted(tx);
                    SidekickLog.d(TAG, "Mocked transaction completed: " + txId +
                            " (rule: " + matchingRule.getId() + ", matches: " + matchingRule.getMatchCount() + ")");

                    return mockResponse;
                } else {
                    SidekickLog.w(TAG, "Failed to build mock response, falling back to real response");
                }
            }

            // Build HttpResponse with full details (real response)
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
                SidekickLog.i(TAG, "<<< " + tx.getResponseCode() + " " + tx.getResponse().getStatusMessage());
            }

            tx.markCompleted();
            NetworkInspector.onTransactionCompleted(tx);
            SidekickLog.d(TAG, "Transaction completed: " + txId + " (duration=" + tx.getDuration() + "ms)");

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
            SidekickLog.w(TAG, "Failed to capture request headers", e);
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
            SidekickLog.w(TAG, "Failed to capture request body", e);
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
            SidekickLog.w(TAG, "Failed to capture response headers", e);
        }
    }

    private void captureResponseBody(Object okResponse, String txId, HttpResponse.Builder builder) {
        try {
            Object body = invoke(okResponse, "body");
            if (body == null) return;

            // Get content type
            Object mediaType = invoke(body, "contentType");
            String contentType = mediaType != null ? mediaType.toString() : null;
            if (contentType != null) {
                builder.contentType(contentType);
            }

            // Get content length
            long contentLength = (long) invoke(body, "contentLength");
            builder.bodySize(contentLength);

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

            // Limit body capture to reasonable size (5MB max for binary)
            int maxBinarySize = 5 * 1024 * 1024;
            if (size > maxBinarySize) {
                SidekickLog.d(TAG, "Response body too large to capture: " + size + " bytes");
                return;
            }

            int maxInlineSize = NetworkInspector.getMaxInlineBodySize();
            boolean isText = isTextContent(mediaType);

            if (isText) {
                // Text content - use string handling
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
                        BodyReference ref = storage.store(txId, "response", bodyContent, contentType);
                        if (ref != null) {
                            builder.bodyRef(ref);
                        }
                    }
                }
            } else {
                // Binary content (images, etc.) - always store to disk
                SidekickLog.d(TAG, "Capturing binary body, contentType=" + contentType + ", size=" + size);
                try {
                    Object clonedBuffer = invoke(buffer, "clone");
                    // Use snapshot() to get a ByteString, then toByteArray() to get byte[]
                    Object byteString = invoke(clonedBuffer, "snapshot");
                    byte[] bytes = (byte[]) invoke(byteString, "toByteArray");
                    SidekickLog.d(TAG, "Got binary bytes, length=" + bytes.length);

                    BodyStorage storage = BodyStorage.getInstance();
                    if (storage != null) {
                        BodyReference ref = storage.store(txId, "response", bytes, contentType);
                        if (ref != null) {
                            builder.bodyRef(ref);
                            SidekickLog.d(TAG, "Stored binary body: " + bytes.length + " bytes, path=" + ref.getPath());
                        } else {
                            SidekickLog.w(TAG, "BodyStorage.store returned null for binary body");
                        }
                    } else {
                        SidekickLog.w(TAG, "BodyStorage not initialized for binary body");
                    }
                } catch (Exception e) {
                    SidekickLog.e(TAG, "Failed to capture binary body", e);
                }
            }
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to capture response body", e);
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
