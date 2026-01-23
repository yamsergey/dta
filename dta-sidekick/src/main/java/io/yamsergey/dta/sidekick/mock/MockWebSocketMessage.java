package io.yamsergey.dta.sidekick.mock;

/**
 * Represents a mock WebSocket message to be returned/sent instead of the real message.
 */
public class MockWebSocketMessage {

    private final String textPayload;
    private final byte[] binaryPayload;
    private final boolean drop;
    private final String capturedMessageId;

    private MockWebSocketMessage(Builder builder) {
        this.textPayload = builder.textPayload;
        this.binaryPayload = builder.binaryPayload;
        this.drop = builder.drop;
        this.capturedMessageId = builder.capturedMessageId;
    }

    // Getters
    public String getTextPayload() {
        return textPayload;
    }

    public byte[] getBinaryPayload() {
        return binaryPayload;
    }

    public boolean isDrop() {
        return drop;
    }

    public String getCapturedMessageId() {
        return capturedMessageId;
    }

    public boolean isText() {
        return textPayload != null;
    }

    public boolean isBinary() {
        return binaryPayload != null;
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static Builder textMessage(String payload) {
        return builder().textPayload(payload);
    }

    public static Builder binaryMessage(byte[] payload) {
        return builder().binaryPayload(payload);
    }

    public static Builder dropMessage() {
        return builder().drop(true);
    }

    public static class Builder {
        private String textPayload;
        private byte[] binaryPayload;
        private boolean drop;
        private String capturedMessageId;

        public Builder textPayload(String textPayload) {
            this.textPayload = textPayload;
            return this;
        }

        public Builder binaryPayload(byte[] binaryPayload) {
            this.binaryPayload = binaryPayload;
            return this;
        }

        public Builder drop(boolean drop) {
            this.drop = drop;
            return this;
        }

        public Builder capturedMessageId(String capturedMessageId) {
            this.capturedMessageId = capturedMessageId;
            return this;
        }

        public MockWebSocketMessage build() {
            return new MockWebSocketMessage(this);
        }
    }
}
