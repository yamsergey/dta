package io.yamsergey.dta.sidekick.mock;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a mock HTTP response to be returned instead of the real response.
 */
public class MockHttpResponse {

    private final int statusCode;
    private final String statusMessage;
    private final Map<String, String> headers;
    private final String body;
    private final String contentType;
    private final String capturedTransactionId;

    private MockHttpResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.statusMessage = builder.statusMessage;
        this.headers = builder.headers != null ? new HashMap<>(builder.headers) : new HashMap<>();
        this.body = builder.body;
        this.contentType = builder.contentType;
        this.capturedTransactionId = builder.capturedTransactionId;
    }

    // Getters
    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }

    public String getCapturedTransactionId() {
        return capturedTransactionId;
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new builder initialized with this response's values.
     * Useful for creating modified copies of a response.
     */
    public Builder toBuilder() {
        return new Builder()
                .statusCode(this.statusCode)
                .statusMessage(this.statusMessage)
                .headers(this.headers != null ? new HashMap<>(this.headers) : null)
                .body(this.body)
                .contentType(this.contentType)
                .capturedTransactionId(this.capturedTransactionId);
    }

    public static class Builder {
        private int statusCode = 200;
        private String statusMessage = "OK";
        private Map<String, String> headers;
        private String body;
        private String contentType;
        private String capturedTransactionId;

        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder statusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder addHeader(String name, String value) {
            if (this.headers == null) {
                this.headers = new HashMap<>();
            }
            this.headers.put(name, value);
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder capturedTransactionId(String capturedTransactionId) {
            this.capturedTransactionId = capturedTransactionId;
            return this;
        }

        public MockHttpResponse build() {
            return new MockHttpResponse(this);
        }
    }
}
