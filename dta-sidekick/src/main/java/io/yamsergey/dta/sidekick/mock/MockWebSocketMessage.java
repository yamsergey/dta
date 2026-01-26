package io.yamsergey.dta.sidekick.mock;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Represents a mock WebSocket message to be returned/sent instead of the real message.
 *
 * <p>This class is immutable. Use the builder or {@code withXxx()} methods to create
 * modified copies:</p>
 *
 * <pre>{@code
 * // Using builder
 * MockWebSocketMessage message = MockWebSocketMessage.builder()
 *     .textPayload("{\"type\": \"mock\"}")
 *     .build();
 *
 * // Using static factory methods
 * MockWebSocketMessage text = MockWebSocketMessage.textMessage("{\"data\": \"test\"}").build();
 * MockWebSocketMessage binary = MockWebSocketMessage.binaryMessage(bytes).build();
 * MockWebSocketMessage dropped = MockWebSocketMessage.dropMessage().build();
 *
 * // Creating modified copy
 * MockWebSocketMessage modified = message.withTextPayload("{\"type\": \"updated\"}");
 *
 * // Using toBuilder for multiple changes
 * MockWebSocketMessage another = message.toBuilder()
 *     .textPayload("{\"type\": \"other\"}")
 *     .capturedMessageId("msg-123")
 *     .build();
 * }</pre>
 */
@Value
@Builder(builderClassName = "Builder", toBuilder = true)
@With
public class MockWebSocketMessage {

    String textPayload;

    byte[] binaryPayload;

    boolean drop;

    /**
     * The ID of the captured message this mock was created from (if any).
     */
    String capturedMessageId;

    /**
     * Returns true if this is a text message mock.
     */
    public boolean isText() {
        return textPayload != null;
    }

    /**
     * Returns true if this is a binary message mock.
     */
    public boolean isBinary() {
        return binaryPayload != null;
    }

    /**
     * Convenience method to create a text message mock builder.
     */
    public static Builder textMessage(String payload) {
        return builder().textPayload(payload);
    }

    /**
     * Convenience method to create a binary message mock builder.
     */
    public static Builder binaryMessage(byte[] payload) {
        return builder().binaryPayload(payload);
    }

    /**
     * Convenience method to create a drop message mock builder.
     */
    public static Builder dropMessage() {
        return builder().drop(true);
    }
}
