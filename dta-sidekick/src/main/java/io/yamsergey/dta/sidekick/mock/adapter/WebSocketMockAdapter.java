package io.yamsergey.dta.sidekick.mock.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.yamsergey.dta.sidekick.mock.MockWebSocketMessage;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * Adapter interface for customizing WebSocket mock messages.
 *
 * <p>Implement this interface to dynamically modify mock messages based on
 * sent/received content, add custom logic, or conditionally skip mocking.</p>
 *
 * <h3>Example usage:</h3>
 * <pre>{@code
 * WebSocketMockAdapter adapter = (originalMessage, proposedMock) -> {
 *     // Echo back the original message with modification
 *     String payload = originalMessage.getTextPayload();
 *     if (payload != null && payload.contains("ping")) {
 *         return MockWebSocketMessage.textMessage(
 *             payload.replace("ping", "pong")
 *         );
 *     }
 *     return proposedMock;
 * };
 *
 * Sidekick.configure(new SidekickConfig.Builder()
 *     .webSocketMockAdapter(adapter)
 *     .build());
 * }</pre>
 */
public interface WebSocketMockAdapter {

    /**
     * Called when a WebSocket message matches a mock rule.
     *
     * <p>This method allows you to:</p>
     * <ul>
     *   <li>Return the proposed mock message unchanged</li>
     *   <li>Modify the message payload</li>
     *   <li>Return null to skip mocking and let the original message through</li>
     *   <li>Return a drop message to suppress the message entirely</li>
     * </ul>
     *
     * @param originalMessage The original WebSocket message (sent or received)
     * @param proposedMock The mock message configured in the matching rule
     * @return The message to use, or null to skip mocking this message
     */
    @Nullable
    MockWebSocketMessage onMockMessage(
            @NonNull WebSocketMessage originalMessage,
            @NonNull MockWebSocketMessage proposedMock
    );
}
