package io.yamsergey.adt.sidekick.events.adapters;

import java.util.HashMap;
import java.util.Map;

import io.yamsergey.adt.sidekick.events.EventAdapter;
import io.yamsergey.adt.sidekick.events.EventType;
import io.yamsergey.adt.sidekick.network.NetworkRequest;

/**
 * Adapter for converting NetworkRequest objects to HTTP event records.
 */
public class HttpEventAdapter implements EventAdapter<NetworkRequest> {

    private static final HttpEventAdapter INSTANCE = new HttpEventAdapter();

    public static HttpEventAdapter getInstance() {
        return INSTANCE;
    }

    @Override
    public EventType getEventType() {
        return EventType.HTTP;
    }

    @Override
    public String getTag() {
        return "http";
    }

    @Override
    public Map<String, Object> toPayload(NetworkRequest request) {
        Map<String, Object> payload = new HashMap<>();

        // Request ID
        payload.put("id", request.getId());

        // Request info
        payload.put("method", request.getMethod());
        payload.put("url", request.getUrl());

        // Timing
        payload.put("startTime", request.getStartTime());
        payload.put("endTime", request.getEndTime());
        payload.put("duration", request.getDuration());

        // Request details
        Map<String, String> reqHeaders = request.getRequestHeaders();
        if (reqHeaders != null && !reqHeaders.isEmpty()) {
            payload.put("requestHeaders", new HashMap<>(reqHeaders));
        }
        if (request.getRequestBody() != null) {
            payload.put("requestBody", request.getRequestBody());
        }
        if (request.getRequestContentType() != null) {
            payload.put("requestContentType", request.getRequestContentType());
        }

        // Response details
        payload.put("responseCode", request.getResponseCode());
        if (request.getResponseMessage() != null) {
            payload.put("responseMessage", request.getResponseMessage());
        }
        Map<String, String> respHeaders = request.getResponseHeaders();
        if (respHeaders != null && !respHeaders.isEmpty()) {
            payload.put("responseHeaders", new HashMap<>(respHeaders));
        }
        if (request.getResponseBody() != null) {
            payload.put("responseBody", request.getResponseBody());
        }
        if (request.getResponseContentType() != null) {
            payload.put("responseContentType", request.getResponseContentType());
        }
        payload.put("responseBodySize", request.getResponseBodySize());

        // Status
        payload.put("status", request.getStatus().name());
        if (request.getError() != null) {
            payload.put("error", request.getError());
        }

        // Metadata
        if (request.getProtocol() != null) {
            payload.put("protocol", request.getProtocol());
        }
        if (request.getSource() != null) {
            payload.put("source", request.getSource());
        }

        return payload;
    }
}
