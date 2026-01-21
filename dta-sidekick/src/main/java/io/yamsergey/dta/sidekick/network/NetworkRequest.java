package io.yamsergey.dta.sidekick.network;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a captured network request with its response.
 */
public class NetworkRequest {

    private final String id;
    private final long startTime;
    private volatile long endTime;
    private volatile long duration;

    // Request data
    private volatile String url;
    private volatile String method;
    private volatile Map<String, String> requestHeaders;
    private volatile String requestBody;
    private volatile String requestContentType;

    // Response data
    private volatile int responseCode;
    private volatile String responseMessage;
    private volatile Map<String, String> responseHeaders;
    private volatile String responseBody;
    private volatile String responseContentType;
    private volatile long responseBodySize;

    // Status
    private volatile Status status;
    private volatile String error;

    // Metadata
    private volatile String protocol;
    private volatile String source;  // e.g., "OkHttp", "HttpURLConnection"

    public enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public NetworkRequest() {
        this.id = UUID.randomUUID().toString();
        this.startTime = System.currentTimeMillis();
        this.status = Status.PENDING;
        this.requestHeaders = new ConcurrentHashMap<>();
        this.responseHeaders = new ConcurrentHashMap<>();
    }

    public NetworkRequest(String url, String method) {
        this();
        this.url = url;
        this.method = method;
    }

    // Getters
    public String getId() { return id; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public long getDuration() { return duration; }
    public String getUrl() { return url; }
    public String getMethod() { return method; }
    public Map<String, String> getRequestHeaders() { return Collections.unmodifiableMap(requestHeaders); }
    public String getRequestBody() { return requestBody; }
    public String getRequestContentType() { return requestContentType; }
    public int getResponseCode() { return responseCode; }
    public String getResponseMessage() { return responseMessage; }
    public Map<String, String> getResponseHeaders() { return Collections.unmodifiableMap(responseHeaders); }
    public String getResponseBody() { return responseBody; }
    public String getResponseContentType() { return responseContentType; }
    public long getResponseBodySize() { return responseBodySize; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public String getProtocol() { return protocol; }
    public String getSource() { return source; }

    // Setters
    public NetworkRequest setUrl(String url) { this.url = url; return this; }
    public NetworkRequest setMethod(String method) { this.method = method; return this; }
    public NetworkRequest setRequestBody(String body) { this.requestBody = body; return this; }
    public NetworkRequest setRequestContentType(String contentType) { this.requestContentType = contentType; return this; }
    public NetworkRequest setResponseCode(int code) { this.responseCode = code; return this; }
    public NetworkRequest setResponseMessage(String message) { this.responseMessage = message; return this; }
    public NetworkRequest setResponseBody(String body) { this.responseBody = body; return this; }
    public NetworkRequest setResponseContentType(String contentType) { this.responseContentType = contentType; return this; }
    public NetworkRequest setResponseBodySize(long size) { this.responseBodySize = size; return this; }
    public NetworkRequest setProtocol(String protocol) { this.protocol = protocol; return this; }
    public NetworkRequest setSource(String source) { this.source = source; return this; }

    public NetworkRequest addRequestHeader(String name, String value) {
        requestHeaders.put(name, value);
        return this;
    }

    public NetworkRequest addResponseHeader(String name, String value) {
        responseHeaders.put(name, value);
        return this;
    }

    public NetworkRequest setRequestHeaders(Map<String, String> headers) {
        this.requestHeaders.clear();
        if (headers != null) {
            this.requestHeaders.putAll(headers);
        }
        return this;
    }

    public NetworkRequest setResponseHeaders(Map<String, String> headers) {
        this.responseHeaders.clear();
        if (headers != null) {
            this.responseHeaders.putAll(headers);
        }
        return this;
    }

    // Status updates
    public NetworkRequest markInProgress() {
        this.status = Status.IN_PROGRESS;
        return this;
    }

    public NetworkRequest markCompleted() {
        this.status = Status.COMPLETED;
        this.endTime = System.currentTimeMillis();
        this.duration = endTime - startTime;
        return this;
    }

    public NetworkRequest markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
        this.duration = endTime - startTime;
        return this;
    }

    public NetworkRequest markCancelled() {
        this.status = Status.CANCELLED;
        this.endTime = System.currentTimeMillis();
        this.duration = endTime - startTime;
        return this;
    }

    @Override
    public String toString() {
        return "NetworkRequest{" +
                "id='" + id + '\'' +
                ", method='" + method + '\'' +
                ", url='" + url + '\'' +
                ", status=" + status +
                ", responseCode=" + responseCode +
                ", duration=" + duration + "ms" +
                '}';
    }
}
