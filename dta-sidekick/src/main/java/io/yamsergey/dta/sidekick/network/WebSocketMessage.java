package io.yamsergey.dta.sidekick.network;

import java.util.UUID;

/**
 * Represents a single WebSocket message (frame).
 *
 * <p>Messages can be either text or binary, and can be sent or received.</p>
 */
public class WebSocketMessage {

    private final String id;
    private final String connectionId;
    private final long timestamp;
    private final Direction direction;
    private final MessageType type;
    private final String textPayload;
    private final byte[] binaryPayload;
    private final int payloadSize;

    private WebSocketMessage(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.connectionId = builder.connectionId;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.direction = builder.direction;
        this.type = builder.type;
        this.textPayload = builder.textPayload;
        this.binaryPayload = builder.binaryPayload;
        this.payloadSize = builder.payloadSize > 0 ? builder.payloadSize : calculatePayloadSize();
    }

    private int calculatePayloadSize() {
        if (textPayload != null) {
            return textPayload.length();
        }
        if (binaryPayload != null) {
            return binaryPayload.length;
        }
        return 0;
    }

    // Getters
    public String getId() { return id; }
    public String getConnectionId() { return connectionId; }
    public long getTimestamp() { return timestamp; }
    public Direction getDirection() { return direction; }
    public MessageType getType() { return type; }
    public String getTextPayload() { return textPayload; }
    public byte[] getBinaryPayload() { return binaryPayload; }
    public int getPayloadSize() { return payloadSize; }

    public boolean isSent() { return direction == Direction.SENT; }
    public boolean isReceived() { return direction == Direction.RECEIVED; }
    public boolean isText() { return type == MessageType.TEXT; }
    public boolean isBinary() { return type == MessageType.BINARY; }

    /**
     * Message direction.
     */
    public enum Direction {
        SENT,
        RECEIVED
    }

    /**
     * Message type.
     */
    public enum MessageType {
        TEXT,
        BINARY,
        PING,
        PONG,
        CLOSE
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static Builder textMessage(String connectionId, Direction direction, String payload) {
        return builder()
                .connectionId(connectionId)
                .direction(direction)
                .type(MessageType.TEXT)
                .textPayload(payload);
    }

    public static Builder binaryMessage(String connectionId, Direction direction, byte[] payload) {
        return builder()
                .connectionId(connectionId)
                .direction(direction)
                .type(MessageType.BINARY)
                .binaryPayload(payload);
    }

    public static class Builder {
        private String id;
        private String connectionId;
        private long timestamp;
        private Direction direction;
        private MessageType type;
        private String textPayload;
        private byte[] binaryPayload;
        private int payloadSize;

        public Builder id(String id) { this.id = id; return this; }
        public Builder connectionId(String connectionId) { this.connectionId = connectionId; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder direction(Direction direction) { this.direction = direction; return this; }
        public Builder type(MessageType type) { this.type = type; return this; }
        public Builder textPayload(String textPayload) { this.textPayload = textPayload; return this; }
        public Builder binaryPayload(byte[] binaryPayload) { this.binaryPayload = binaryPayload; return this; }
        public Builder payloadSize(int payloadSize) { this.payloadSize = payloadSize; return this; }

        public WebSocketMessage build() {
            return new WebSocketMessage(this);
        }
    }
}
