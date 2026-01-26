package io.yamsergey.dta.sidekick.mock.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.yamsergey.dta.sidekick.mock.MockWebSocketMessage;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * Default WebSocket mock adapter that returns the proposed mock message unchanged.
 *
 * <p>This adapter replicates the default mocking behavior where the configured
 * mock message is returned as-is without any modifications.</p>
 */
public class DefaultWebSocketMockAdapter implements WebSocketMockAdapter {

    private static final DefaultWebSocketMockAdapter INSTANCE = new DefaultWebSocketMockAdapter();

    private DefaultWebSocketMockAdapter() {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static DefaultWebSocketMockAdapter getInstance() {
        return INSTANCE;
    }

    @Nullable
    @Override
    public MockWebSocketMessage onMockMessage(
            @NonNull WebSocketMessage originalMessage,
            @NonNull MockWebSocketMessage proposedMock
    ) {
        return proposedMock;
    }
}
