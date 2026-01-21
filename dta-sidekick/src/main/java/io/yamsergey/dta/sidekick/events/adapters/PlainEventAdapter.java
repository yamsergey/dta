package io.yamsergey.dta.sidekick.events.adapters;

import java.util.HashMap;
import java.util.Map;

import io.yamsergey.dta.sidekick.events.EventAdapter;
import io.yamsergey.dta.sidekick.events.EventType;

/**
 * Adapter for plain text/debug events.
 *
 * <p>Use this adapter for simple logging or debugging events that don't fit
 * a specific category like HTTP or WebSocket.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * PlainEvent event = new PlainEvent("User logged in", PlainEvent.Level.INFO);
 * event.put("userId", "12345");
 * eventStore.record(PlainEventAdapter.getInstance(), event);
 * }</pre>
 */
public class PlainEventAdapter implements EventAdapter<PlainEventAdapter.PlainEvent> {

    private static final PlainEventAdapter INSTANCE = new PlainEventAdapter();

    public static PlainEventAdapter getInstance() {
        return INSTANCE;
    }

    @Override
    public EventType getEventType() {
        return EventType.PLAIN;
    }

    @Override
    public String getTag() {
        return "log";
    }

    @Override
    public Map<String, Object> toPayload(PlainEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", event.getMessage());
        payload.put("level", event.getLevel().name());

        if (event.getCategory() != null) {
            payload.put("category", event.getCategory());
        }

        // Include any extra data
        Map<String, Object> extra = event.getExtra();
        if (extra != null && !extra.isEmpty()) {
            payload.put("extra", extra);
        }

        return payload;
    }

    /**
     * A plain event with a message and optional metadata.
     */
    public static class PlainEvent {

        public enum Level {
            DEBUG, INFO, WARN, ERROR
        }

        private final String message;
        private final Level level;
        private String category;
        private Map<String, Object> extra;

        public PlainEvent(String message) {
            this(message, Level.INFO);
        }

        public PlainEvent(String message, Level level) {
            this.message = message;
            this.level = level;
        }

        public String getMessage() {
            return message;
        }

        public Level getLevel() {
            return level;
        }

        public String getCategory() {
            return category;
        }

        public PlainEvent setCategory(String category) {
            this.category = category;
            return this;
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public PlainEvent put(String key, Object value) {
            if (extra == null) {
                extra = new HashMap<>();
            }
            extra.put(key, value);
            return this;
        }
    }
}
