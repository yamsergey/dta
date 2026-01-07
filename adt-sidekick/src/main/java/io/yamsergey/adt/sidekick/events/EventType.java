package io.yamsergey.adt.sidekick.events;

/**
 * Types of events that can be captured by the debug system.
 */
public enum EventType {
    /**
     * HTTP request/response events (REST API calls, etc.)
     */
    HTTP((byte) 0x01),

    /**
     * WebSocket frame events (messages, connections, disconnections)
     */
    WEBSOCKET((byte) 0x02),

    /**
     * Plain text events (logs, debug messages)
     */
    PLAIN((byte) 0x03),

    /**
     * Custom user-defined events
     */
    CUSTOM((byte) 0x80);

    private final byte code;

    EventType(byte code) {
        this.code = code;
    }

    /**
     * Returns the byte code for this event type (used in binary format).
     */
    public byte getCode() {
        return code;
    }

    /**
     * Returns the event type for the given byte code.
     */
    public static EventType fromCode(byte code) {
        for (EventType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return CUSTOM;
    }
}
