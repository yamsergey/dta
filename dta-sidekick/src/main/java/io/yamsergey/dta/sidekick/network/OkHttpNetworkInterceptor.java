package io.yamsergey.dta.sidekick.network;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * OkHttp Interceptor that captures network requests for inspection.
 *
 * <p>This interceptor captures request and response data and records it
 * with the NetworkInspector using {@link HttpTransaction}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * OkHttpClient client = new OkHttpClient.Builder()
 *     .addInterceptor(new OkHttpNetworkInterceptor())
 *     .build();
 * }</pre>
 *
 * <p>For network-level inspection (after redirects), use as a network interceptor:</p>
 * <pre>{@code
 * OkHttpClient client = new OkHttpClient.Builder()
 *     .addNetworkInterceptor(new OkHttpNetworkInterceptor())
 *     .build();
 * }</pre>
 */
public class OkHttpNetworkInterceptor implements Interceptor {

    private static final String TAG = "OkHttpInterceptor";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Override
    public Response intercept(Chain chain) throws IOException {
        if (!NetworkInspector.isEnabled()) {
            return chain.proceed(chain.request());
        }

        Request request = chain.request();
        HttpTransaction transaction = createTransaction(request);

        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            transaction.markFailed(e.getMessage());
            NetworkInspector.onTransactionCompleted(transaction);
            throw e;
        }

        return processResponse(response, transaction);
    }

    private HttpTransaction createTransaction(Request request) {
        HttpRequest.Builder requestBuilder = HttpRequest.builder()
                .url(request.url().toString())
                .method(request.method());

        // Capture request headers
        Headers headers = request.headers();
        for (int i = 0; i < headers.size(); i++) {
            requestBuilder.addHeader(headers.name(i), headers.value(i));
        }

        // Capture request body
        if (NetworkInspector.isCaptureRequestBody()) {
            RequestBody requestBody = request.body();
            if (requestBody != null) {
                try {
                    MediaType contentType = requestBody.contentType();
                    if (contentType != null) {
                        requestBuilder.contentType(contentType.toString());
                    }

                    long contentLength = requestBody.contentLength();
                    requestBuilder.bodySize(contentLength);

                    if (isTextContent(contentType) && contentLength <= NetworkInspector.getMaxInlineBodySize()) {
                        Buffer buffer = new Buffer();
                        requestBody.writeTo(buffer);
                        requestBuilder.body(buffer.readString(getCharset(contentType)));
                    }
                } catch (Exception e) {
                    SidekickLog.w(TAG, "Failed to capture request body", e);
                }
            }
        }

        return NetworkInspector.startTransaction(requestBuilder.build(), "OkHttp");
    }

    private Response processResponse(Response response, HttpTransaction transaction) throws IOException {
        HttpResponse.Builder responseBuilder = HttpResponse.builder()
                .statusCode(response.code())
                .statusMessage(response.message())
                .protocol(response.protocol().toString());

        // Capture response headers
        Headers headers = response.headers();
        for (int i = 0; i < headers.size(); i++) {
            responseBuilder.addHeader(headers.name(i), headers.value(i));
        }

        // Capture response body
        ResponseBody responseBody = response.body();
        if (responseBody != null && NetworkInspector.isCaptureResponseBody()) {
            MediaType contentType = responseBody.contentType();
            if (contentType != null) {
                responseBuilder.contentType(contentType.toString());
            }

            long contentLength = responseBody.contentLength();
            responseBuilder.bodySize(contentLength);

            if (isTextContent(contentType) &&
                    (contentLength == -1 || contentLength <= NetworkInspector.getMaxInlineBodySize())) {
                try {
                    BufferedSource source = responseBody.source();
                    source.request(Long.MAX_VALUE); // Buffer the entire body
                    Buffer buffer = source.getBuffer();

                    long size = buffer.size();
                    responseBuilder.bodySize(size);

                    if (size <= NetworkInspector.getMaxInlineBodySize()) {
                        String body = buffer.clone().readString(getCharset(contentType));
                        responseBuilder.body(body);
                    } else {
                        // Store large body to disk
                        String body = buffer.clone().readString(getCharset(contentType));
                        BodyStorage storage = BodyStorage.getInstance();
                        if (storage != null) {
                            BodyReference ref = storage.store(transaction.getId(), "response", body,
                                    contentType != null ? contentType.toString() : null);
                            if (ref != null) {
                                responseBuilder.bodyRef(ref);
                            }
                        }
                    }
                } catch (Exception e) {
                    SidekickLog.w(TAG, "Failed to capture response body", e);
                }
            }
        }

        transaction.setResponse(responseBuilder.build());
        transaction.markCompleted();
        NetworkInspector.onTransactionCompleted(transaction);
        SidekickLog.d(TAG, "Completed: " + transaction.getId() + " " + response.code());

        return response;
    }

    private boolean isTextContent(MediaType contentType) {
        if (contentType == null) {
            return false;
        }

        String type = contentType.type();
        String subtype = contentType.subtype();

        // Text types
        if ("text".equals(type)) {
            return true;
        }

        // JSON, XML, etc.
        if ("application".equals(type)) {
            return subtype != null && (
                    subtype.contains("json") ||
                    subtype.contains("xml") ||
                    subtype.contains("javascript") ||
                    subtype.contains("x-www-form-urlencoded")
            );
        }

        return false;
    }

    private Charset getCharset(MediaType contentType) {
        if (contentType == null) {
            return UTF8;
        }
        Charset charset = contentType.charset(UTF8);
        return charset != null ? charset : UTF8;
    }
}
