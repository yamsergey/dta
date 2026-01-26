package io.yamsergey.dta.sidekick.mock.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.yamsergey.dta.sidekick.mock.MockHttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;

/**
 * Adapter interface for customizing HTTP mock responses.
 *
 * <p>Implement this interface to dynamically modify mock responses based on
 * request content, add custom logic, or conditionally skip mocking.</p>
 *
 * <h3>Example usage:</h3>
 * <pre>{@code
 * HttpMockAdapter adapter = (transaction, proposedResponse) -> {
 *     // Customize response based on request
 *     String userId = transaction.getRequestHeaders().get("X-User-Id");
 *     if (userId != null) {
 *         return proposedResponse.toBuilder()
 *             .body("{\"userId\": \"" + userId + "\"}")
 *             .build();
 *     }
 *     return proposedResponse;
 * };
 *
 * Sidekick.configure(new SidekickConfig.Builder()
 *     .httpMockAdapter(adapter)
 *     .build());
 * }</pre>
 */
public interface HttpMockAdapter {

    /**
     * Called when an HTTP request matches a mock rule.
     *
     * <p>This method allows you to:</p>
     * <ul>
     *   <li>Return the proposed response unchanged</li>
     *   <li>Modify the response (status, headers, body)</li>
     *   <li>Return null to skip mocking and let the real request proceed</li>
     * </ul>
     *
     * @param transaction The intercepted HTTP transaction containing request details
     * @param proposedResponse The mock response configured in the matching rule
     * @return The response to use, or null to skip mocking this request
     */
    @Nullable
    MockHttpResponse onMockRequest(
            @NonNull HttpTransaction transaction,
            @NonNull MockHttpResponse proposedResponse
    );
}
