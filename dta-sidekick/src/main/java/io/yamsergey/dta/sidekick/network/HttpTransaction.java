package io.yamsergey.dta.sidekick.network;

import java.util.UUID;

/**
 * Represents a complete HTTP transaction including request, response, timing, and status.
 *
 * <p>An HttpTransaction captures all information about an HTTP request/response cycle:</p>
 * <ul>
 *   <li>The request that was sent ({@link HttpRequest})</li>
 *   <li>The response that was received ({@link HttpResponse}), if available</li>
 *   <li>Timing information (start time, end time, duration)</li>
 *   <li>Status (in progress, completed, failed)</li>
 *   <li>Error information if the request failed</li>
 *   <li>Metadata like source library (OkHttp, HttpURLConnection, etc.)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Create a new transaction when request starts
 * HttpTransaction tx = HttpTransaction.create()
 *     .request(HttpRequest.builder()
 *         .url("https://api.example.com/users")
 *         .method("GET")
 *         .addHeader("Authorization", "Bearer token")
 *         .build())
 *     .source("OkHttp")
 *     .build();
 *
 * // Update with response when received
 * tx.setResponse(HttpResponse.builder()
 *     .statusCode(200)
 *     .statusMessage("OK")
 *     .addHeader("Content-Type", "application/json")
 *     .body("{\"users\": []}")
 *     .build());
 * tx.markCompleted();
 * }</pre>
 */
public final class HttpTransaction {

    /**
     * Status of the HTTP transaction.
     */
    public enum Status {
        /** Request is being prepared or sent */
        PENDING,
        /** Request has been sent, waiting for response */
        IN_PROGRESS,
        /** Response received successfully */
        COMPLETED,
        /** Request failed with an error */
        FAILED,
        /** Request was cancelled */
        CANCELLED
    }

    private final String id;
    private final long startTime;
    private volatile long endTime;
    private volatile long duration;

    private volatile HttpRequest request;
    private volatile HttpResponse response;

    private volatile Status status;
    private volatile String error;
    private volatile String source;
    private volatile String resourceType;
    private volatile boolean mocked;
    private volatile String mockRuleId;

    private HttpTransaction(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.startTime = builder.startTime > 0 ? builder.startTime : System.currentTimeMillis();
        this.request = builder.request;
        this.source = builder.source;
        this.status = Status.PENDING;
    }

    // =========================================================================
    // Getters
    // =========================================================================

    /**
     * Gets the unique identifier for this transaction.
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the time when the request was initiated (milliseconds since epoch).
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Gets the time when the transaction completed (milliseconds since epoch).
     *
     * @return end time, or 0 if not yet completed
     */
    public long getEndTime() {
        return endTime;
    }

    /**
     * Gets the total duration of the transaction in milliseconds.
     *
     * @return duration, or 0 if not yet completed
     */
    public long getDuration() {
        return duration;
    }

    /**
     * Gets the HTTP request.
     */
    public HttpRequest getRequest() {
        return request;
    }

    /**
     * Gets the HTTP response, if available.
     *
     * @return the response, or null if not yet received or if the request failed
     */
    public HttpResponse getResponse() {
        return response;
    }

    /**
     * Gets the current status of the transaction.
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Gets the error message if the request failed.
     *
     * @return error message, or null if no error
     */
    public String getError() {
        return error;
    }

    /**
     * Gets the source library that made this request (e.g., "OkHttp", "HttpURLConnection").
     */
    public String getSource() {
        return source;
    }

    /**
     * Gets the CDP resource type (e.g., "Document", "XHR", "Script", "Stylesheet", "Image", "Font").
     * Only set for Custom Tab requests captured via Chrome DevTools Protocol.
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Returns true if this transaction was mocked (response came from a mock rule).
     */
    public boolean isMocked() {
        return mocked;
    }

    /**
     * Gets the ID of the mock rule that was applied, if any.
     */
    public String getMockRuleId() {
        return mockRuleId;
    }

    // =========================================================================
    // Setters / Mutators
    // =========================================================================

    /**
     * Sets the HTTP request.
     */
    public HttpTransaction setRequest(HttpRequest request) {
        this.request = request;
        return this;
    }

    /**
     * Sets the HTTP response.
     */
    public HttpTransaction setResponse(HttpResponse response) {
        this.response = response;
        return this;
    }

    /**
     * Sets the source library.
     */
    public HttpTransaction setSource(String source) {
        this.source = source;
        return this;
    }

    /**
     * Sets the CDP resource type.
     */
    public HttpTransaction setResourceType(String resourceType) {
        this.resourceType = resourceType;
        return this;
    }

    /**
     * Sets whether this transaction was mocked.
     */
    public HttpTransaction setMocked(boolean mocked) {
        this.mocked = mocked;
        return this;
    }

    /**
     * Sets the mock rule ID that was applied.
     */
    public HttpTransaction setMockRuleId(String mockRuleId) {
        this.mockRuleId = mockRuleId;
        return this;
    }

    // =========================================================================
    // Status Updates
    // =========================================================================

    /**
     * Marks the transaction as in progress (request sent, waiting for response).
     */
    public HttpTransaction markInProgress() {
        this.status = Status.IN_PROGRESS;
        return this;
    }

    /**
     * Marks the transaction as completed successfully.
     */
    public HttpTransaction markCompleted() {
        this.status = Status.COMPLETED;
        this.endTime = System.currentTimeMillis();
        this.duration = endTime - startTime;
        return this;
    }

    /**
     * Marks the transaction as failed with an error.
     *
     * @param error the error message
     */
    public HttpTransaction markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
        this.duration = endTime - startTime;
        return this;
    }

    /**
     * Marks the transaction as cancelled.
     */
    public HttpTransaction markCancelled() {
        this.status = Status.CANCELLED;
        this.endTime = System.currentTimeMillis();
        this.duration = endTime - startTime;
        return this;
    }

    // =========================================================================
    // Convenience Methods
    // =========================================================================

    /**
     * Returns true if the transaction has completed (successfully or with error).
     */
    public boolean isComplete() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    /**
     * Returns true if the transaction completed successfully.
     */
    public boolean isSuccessful() {
        return status == Status.COMPLETED && response != null && response.isSuccessful();
    }

    /**
     * Gets the URL from the request.
     */
    public String getUrl() {
        return request != null ? request.getUrl() : null;
    }

    /**
     * Gets the HTTP method from the request.
     */
    public String getMethod() {
        return request != null ? request.getMethod() : null;
    }

    /**
     * Gets the response status code.
     *
     * @return status code, or 0 if no response
     */
    public int getResponseCode() {
        return response != null ? response.getStatusCode() : 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HttpTransaction{");
        sb.append("id='").append(id).append('\'');
        if (request != null) {
            sb.append(", ").append(request.getMethod()).append(" ").append(request.getUrl());
        }
        sb.append(", status=").append(status);
        if (response != null) {
            sb.append(", response=").append(response.getStatusCode());
        }
        sb.append(", duration=").append(duration).append("ms");
        sb.append('}');
        return sb.toString();
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static Builder create() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private long startTime;
        private HttpRequest request;
        private String source;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder startTime(long startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder request(HttpRequest request) {
            this.request = request;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public HttpTransaction build() {
            return new HttpTransaction(this);
        }
    }
}
