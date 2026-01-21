package io.yamsergey.dta.sidekick.events;

import java.util.Map;
import java.util.UUID;

/**
 * A generic event record that wraps type-specific event data.
 * This is the format that gets serialized to the binary event store.
 */
public final class EventRecord {

    private final String id;
    private final long timestamp;
    private final EventType type;
    private final String tag;
    private final Map<String, Object> payload;

    public EventRecord(EventType type, String tag, Map<String, Object> payload) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.tag = tag;
        this.payload = payload;
    }

    public EventRecord(String id, long timestamp, EventType type, String tag, Map<String, Object> payload) {
        this.id = id;
        this.timestamp = timestamp;
        this.type = type;
        this.tag = tag;
        this.payload = payload;
    }

    public String getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public EventType getType() {
        return type;
    }

    public String getTag() {
        return tag;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "EventRecord{" +
                "id='" + id + '\'' +
                ", timestamp=" + timestamp +
                ", type=" + type +
                ", tag='" + tag + '\'' +
                '}';
    }
}
