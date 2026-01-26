package io.yamsergey.dta.sidekick.mock;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Represents a mock HTTP response to be returned instead of the real response.
 *
 * <p>This class is immutable. Use the builder or {@code withXxx()} methods to create
 * modified copies:</p>
 *
 * <pre>{@code
 * // Using builder
 * MockHttpResponse response = MockHttpResponse.builder()
 *     .statusCode(200)
 *     .body("{\"success\": true}")
 *     .build();
 *
 * // Creating modified copy
 * MockHttpResponse modified = response
 *     .withBody("{\"userId\": \"123\"}")
 *     .withStatusCode(201);
 *
 * // Using toBuilder for multiple changes
 * MockHttpResponse another = response.toBuilder()
 *     .statusCode(404)
 *     .statusMessage("Not Found")
 *     .build();
 * }</pre>
 */
@Value
@Builder(builderClassName = "Builder", toBuilder = true)
@With
public class MockHttpResponse {

    @Builder.Default
    int statusCode = 200;

    @Builder.Default
    String statusMessage = "OK";

    @Builder.Default
    Map<String, String> headers = new HashMap<>();

    String body;

    String contentType;

    /**
     * The ID of the captured transaction this mock was created from (if any).
     */
    String capturedTransactionId;

    /**
     * Convenience method to add a header to this response.
     * Returns a new response with the header added.
     */
    public MockHttpResponse withHeader(String name, String value) {
        Map<String, String> newHeaders = new HashMap<>(this.headers != null ? this.headers : new HashMap<>());
        newHeaders.put(name, value);
        return this.withHeaders(newHeaders);
    }

    /**
     * Custom builder methods that extend Lombok's generated builder.
     * Lombok will merge this with the generated builder.
     */
    public static class Builder {
        // Declare the field so we can access it in our custom method
        private Map<String, String> headers;

        /**
         * Convenience method to add a header during building.
         */
        public Builder addHeader(String name, String value) {
            if (this.headers == null) {
                this.headers = new HashMap<>();
            }
            this.headers.put(name, value);
            return this;
        }
    }
}
