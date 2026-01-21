package io.yamsergey.dta.sidekick.events;

import java.util.Map;

/**
 * Adapter interface for converting domain-specific objects to event records.
 *
 * <p>Each adapter is responsible for a specific event type and knows how to
 * extract the relevant data from source objects into a Map that can be
 * serialized with MessagePack.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * public class HttpEventAdapter implements EventAdapter<NetworkRequest> {
 *     @Override
 *     public EventType getEventType() { return EventType.HTTP; }
 *
 *     @Override
 *     public String getTag() { return "http"; }
 *
 *     @Override
 *     public Map<String, Object> toPayload(NetworkRequest request) {
 *         Map<String, Object> payload = new HashMap<>();
 *         payload.put("url", request.getUrl());
 *         payload.put("method", request.getMethod());
 *         // ... etc
 *         return payload;
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of source object this adapter handles
 */
public interface EventAdapter<T> {

    /**
     * Returns the event type this adapter produces.
     */
    EventType getEventType();

    /**
     * Returns the tag prefix for events produced by this adapter.
     * Used for filtering and grouping related events.
     */
    String getTag();

    /**
     * Converts a source object to a payload Map for MessagePack serialization.
     *
     * <p>The map should contain only primitive types, strings, lists, and nested maps
     * that can be serialized by MessagePack.</p>
     *
     * @param source the source object to convert
     * @return a map of key-value pairs representing the event data
     */
    Map<String, Object> toPayload(T source);

    /**
     * Creates an EventRecord from a source object.
     * Default implementation uses getEventType(), getTag(), and toPayload().
     *
     * @param source the source object
     * @return the event record
     */
    default EventRecord toRecord(T source) {
        return new EventRecord(getEventType(), getTag(), toPayload(source));
    }
}
