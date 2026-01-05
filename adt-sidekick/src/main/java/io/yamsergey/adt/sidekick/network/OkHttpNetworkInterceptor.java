package io.yamsergey.adt.sidekick.network;

import android.util.Log;

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
 * with the NetworkInspector.</p>
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
        NetworkRequest networkRequest = createNetworkRequest(request);

        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            networkRequest.markFailed(e.getMessage());
            throw e;
        }

        return processResponse(response, networkRequest);
    }

    private NetworkRequest createNetworkRequest(Request request) {
        NetworkRequest networkRequest = NetworkInspector.startRequest(
                request.url().toString(),
                request.method()
        );

        networkRequest.setSource("OkHttp");
        networkRequest.setProtocol("HTTP/1.1"); // Will be updated from response

        // Capture request headers
        Headers headers = request.headers();
        for (int i = 0; i < headers.size(); i++) {
            networkRequest.addRequestHeader(headers.name(i), headers.value(i));
        }

        // Capture request body
        if (NetworkInspector.isCaptureRequestBody()) {
            RequestBody requestBody = request.body();
            if (requestBody != null) {
                try {
                    MediaType contentType = requestBody.contentType();
                    if (contentType != null) {
                        networkRequest.setRequestContentType(contentType.toString());
                    }

                    if (isTextContent(contentType) && requestBody.contentLength() <= NetworkInspector.getMaxBodySize()) {
                        Buffer buffer = new Buffer();
                        requestBody.writeTo(buffer);
                        networkRequest.setRequestBody(buffer.readString(getCharset(contentType)));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to capture request body", e);
                }
            }
        }

        return networkRequest;
    }

    private Response processResponse(Response response, NetworkRequest networkRequest) throws IOException {
        networkRequest.setResponseCode(response.code());
        networkRequest.setResponseMessage(response.message());
        networkRequest.setProtocol(response.protocol().toString());

        // Capture response headers
        Headers headers = response.headers();
        for (int i = 0; i < headers.size(); i++) {
            networkRequest.addResponseHeader(headers.name(i), headers.value(i));
        }

        // Capture response body
        ResponseBody responseBody = response.body();
        if (responseBody != null && NetworkInspector.isCaptureResponseBody()) {
            MediaType contentType = responseBody.contentType();
            if (contentType != null) {
                networkRequest.setResponseContentType(contentType.toString());
            }

            long contentLength = responseBody.contentLength();
            networkRequest.setResponseBodySize(contentLength);

            if (isTextContent(contentType) &&
                    (contentLength == -1 || contentLength <= NetworkInspector.getMaxBodySize())) {
                try {
                    BufferedSource source = responseBody.source();
                    source.request(Long.MAX_VALUE); // Buffer the entire body
                    Buffer buffer = source.getBuffer();

                    long size = buffer.size();
                    networkRequest.setResponseBodySize(size);

                    if (size <= NetworkInspector.getMaxBodySize()) {
                        String body = buffer.clone().readString(getCharset(contentType));
                        networkRequest.setResponseBody(body);
                    } else {
                        networkRequest.setResponseBody("[Body too large: " + size + " bytes]");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to capture response body", e);
                }
            }
        }

        networkRequest.markCompleted();
        Log.d(TAG, "Completed: " + networkRequest);

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
