package io.yamsergey.adt.sidekick.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an HTTP response with all its details.
 *
 * <p>Contains:</p>
 * <ul>
 *   <li>Status code and message</li>
 *   <li>Headers (supports multiple values per name)</li>
 *   <li>Body content and metadata</li>
 *   <li>Protocol version</li>
 * </ul>
 */
public final class HttpResponse {

    private final int statusCode;
    private final String statusMessage;
    private final List<HttpHeader> headers;
    private final String body;           // Inline body (for small bodies)
    private final BodyReference bodyRef; // Reference to stored body (for large bodies)
    private final long bodySize;
    private final String contentType;
    private final String protocol;

    private HttpResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.statusMessage = builder.statusMessage;
        this.headers = Collections.unmodifiableList(new ArrayList<>(builder.headers));
        this.body = builder.body;
        this.bodyRef = builder.bodyRef;
        this.bodySize = builder.bodySize;
        this.contentType = builder.contentType;
        this.protocol = builder.protocol;
    }

    /**
     * Gets the HTTP status code (e.g., 200, 404, 500).
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Gets the HTTP status message (e.g., "OK", "Not Found").
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Gets all response headers.
     */
    public List<HttpHeader> getHeaders() {
        return headers;
    }

    /**
     * Gets the first header value with the given name (case-insensitive).
     *
     * @param name the header name
     * @return the header value, or null if not found
     */
    public String getHeader(String name) {
        for (HttpHeader header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /**
     * Gets all header values with the given name (case-insensitive).
     *
     * @param name the header name
     * @return list of header values (may be empty)
     */
    public List<String> getHeaders(String name) {
        List<String> values = new ArrayList<>();
        for (HttpHeader header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                values.add(header.getValue());
            }
        }
        return values;
    }

    /**
     * Gets the response body as a string (inline body).
     *
     * @return the body content, or null if not captured or stored externally
     */
    public String getBody() {
        return body;
    }

    /**
     * Gets the body reference for large bodies stored on disk.
     *
     * @return body reference, or null if body is inline or not captured
     */
    public BodyReference getBodyRef() {
        return bodyRef;
    }

    /**
     * Returns true if the body is stored externally (referenced).
     */
    public boolean hasExternalBody() {
        return bodyRef != null;
    }

    /**
     * Gets the size of the response body in bytes.
     *
     * @return body size, or -1 if unknown
     */
    public long getBodySize() {
        return bodySize;
    }

    /**
     * Gets the Content-Type of the response.
     *
     * @return content type, or null if not set
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Gets the HTTP protocol version (e.g., "HTTP/1.1", "h2").
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Returns true if the response indicates success (2xx status code).
     */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public String toString() {
        return statusCode + " " + statusMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int statusCode;
        private String statusMessage;
        private final List<HttpHeader> headers = new ArrayList<>();
        private String body;
        private BodyReference bodyRef;
        private long bodySize = -1;
        private String contentType;
        private String protocol = "HTTP/1.1";

        public Builder statusCode(int code) {
            this.statusCode = code;
            return this;
        }

        public Builder statusMessage(String message) {
            this.statusMessage = message;
            return this;
        }

        public Builder addHeader(String name, String value) {
            this.headers.add(new HttpHeader(name, value));
            return this;
        }

        public Builder addHeader(HttpHeader header) {
            this.headers.add(header);
            return this;
        }

        public Builder headers(List<HttpHeader> headers) {
            this.headers.clear();
            if (headers != null) {
                this.headers.addAll(headers);
            }
            return this;
        }

        /**
         * Sets the inline body content (for small bodies).
         */
        public Builder body(String body) {
            this.body = body;
            this.bodyRef = null; // Clear reference if setting inline
            return this;
        }

        /**
         * Sets the body reference (for large bodies stored on disk).
         */
        public Builder bodyRef(BodyReference ref) {
            this.bodyRef = ref;
            this.body = null; // Clear inline body if setting reference
            if (ref != null) {
                this.bodySize = ref.getSize();
                if (this.contentType == null) {
                    this.contentType = ref.getContentType();
                }
            }
            return this;
        }

        public Builder bodySize(long size) {
            this.bodySize = size;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public HttpResponse build() {
            return new HttpResponse(this);
        }
    }
}
