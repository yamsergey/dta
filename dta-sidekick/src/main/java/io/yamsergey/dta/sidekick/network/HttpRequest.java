package io.yamsergey.dta.sidekick.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an HTTP request with all its details.
 *
 * <p>Contains:</p>
 * <ul>
 *   <li>URL and HTTP method</li>
 *   <li>Headers (supports multiple values per name)</li>
 *   <li>Body content and metadata</li>
 * </ul>
 */
public final class HttpRequest {

    private final String url;
    private final String method;
    private final List<HttpHeader> headers;
    private final String body;           // Inline body (for small bodies)
    private final BodyReference bodyRef; // Reference to stored body (for large bodies)
    private final long bodySize;
    private final String contentType;

    private HttpRequest(Builder builder) {
        this.url = builder.url;
        this.method = builder.method;
        this.headers = Collections.unmodifiableList(new ArrayList<>(builder.headers));
        this.body = builder.body;
        this.bodyRef = builder.bodyRef;
        this.bodySize = builder.bodySize;
        this.contentType = builder.contentType;
    }

    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

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
     * Gets the request body as a string (inline body).
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
     * Gets the size of the request body in bytes.
     *
     * @return body size, or -1 if unknown
     */
    public long getBodySize() {
        return bodySize;
    }

    /**
     * Gets the Content-Type of the request.
     *
     * @return content type, or null if not set
     */
    public String getContentType() {
        return contentType;
    }

    @Override
    public String toString() {
        return method + " " + url;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String url;
        private String method = "GET";
        private final List<HttpHeader> headers = new ArrayList<>();
        private String body;
        private BodyReference bodyRef;
        private long bodySize = -1;
        private String contentType;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
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

        public HttpRequest build() {
            if (url == null) {
                throw new IllegalStateException("URL is required");
            }
            return new HttpRequest(this);
        }
    }
}
