package io.yamsergey.dta.sidekick.mock.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.yamsergey.dta.sidekick.mock.MockHttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;

/**
 * Default HTTP mock adapter that returns the proposed response unchanged.
 *
 * <p>This adapter replicates the default mocking behavior where the configured
 * mock response is returned as-is without any modifications.</p>
 */
public class DefaultHttpMockAdapter implements HttpMockAdapter {

    private static final DefaultHttpMockAdapter INSTANCE = new DefaultHttpMockAdapter();

    private DefaultHttpMockAdapter() {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static DefaultHttpMockAdapter getInstance() {
        return INSTANCE;
    }

    @Nullable
    @Override
    public MockHttpResponse onMockRequest(
            @NonNull HttpTransaction transaction,
            @NonNull MockHttpResponse proposedResponse
    ) {
        return proposedResponse;
    }
}
