package io.yamsergey.dta.sidekick.events.adapters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.yamsergey.dta.sidekick.events.EventAdapter;
import io.yamsergey.dta.sidekick.events.EventType;
import io.yamsergey.dta.sidekick.network.BodyReference;
import io.yamsergey.dta.sidekick.network.HttpHeader;
import io.yamsergey.dta.sidekick.network.HttpRequest;
import io.yamsergey.dta.sidekick.network.HttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;

/**
 * Adapter for converting HttpTransaction objects to HTTP event records.
 *
 * <p>Serializes the complete HTTP transaction including request, response,
 * timing, and metadata to a format suitable for binary storage and transmission.</p>
 */
public class HttpEventAdapter implements EventAdapter<HttpTransaction> {

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
    public Map<String, Object> toPayload(HttpTransaction transaction) {
        Map<String, Object> payload = new HashMap<>();

        // Transaction metadata
        payload.put("id", transaction.getId());
        payload.put("status", transaction.getStatus().name());
        payload.put("startTime", transaction.getStartTime());
        payload.put("endTime", transaction.getEndTime());
        payload.put("duration", transaction.getDuration());

        if (transaction.getError() != null) {
            payload.put("error", transaction.getError());
        }
        if (transaction.getSource() != null) {
            payload.put("source", transaction.getSource());
        }

        // Request
        HttpRequest request = transaction.getRequest();
        if (request != null) {
            payload.put("request", serializeRequest(request));
        }

        // Response
        HttpResponse response = transaction.getResponse();
        if (response != null) {
            payload.put("response", serializeResponse(response));
        }

        return payload;
    }

    private Map<String, Object> serializeRequest(HttpRequest request) {
        Map<String, Object> data = new HashMap<>();

        data.put("url", request.getUrl());
        data.put("method", request.getMethod());

        List<HttpHeader> headers = request.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            data.put("headers", serializeHeaders(headers));
        }

        // Body: either inline or reference
        if (request.hasExternalBody()) {
            data.put("bodyRef", serializeBodyRef(request.getBodyRef()));
        } else if (request.getBody() != null) {
            data.put("body", request.getBody());
        }

        if (request.getBodySize() >= 0) {
            data.put("bodySize", request.getBodySize());
        }
        if (request.getContentType() != null) {
            data.put("contentType", request.getContentType());
        }

        return data;
    }

    private Map<String, Object> serializeResponse(HttpResponse response) {
        Map<String, Object> data = new HashMap<>();

        data.put("statusCode", response.getStatusCode());
        if (response.getStatusMessage() != null) {
            data.put("statusMessage", response.getStatusMessage());
        }
        if (response.getProtocol() != null) {
            data.put("protocol", response.getProtocol());
        }

        List<HttpHeader> headers = response.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            data.put("headers", serializeHeaders(headers));
        }

        // Body: either inline or reference
        if (response.hasExternalBody()) {
            data.put("bodyRef", serializeBodyRef(response.getBodyRef()));
        } else if (response.getBody() != null) {
            data.put("body", response.getBody());
        }

        if (response.getBodySize() >= 0) {
            data.put("bodySize", response.getBodySize());
        }
        if (response.getContentType() != null) {
            data.put("contentType", response.getContentType());
        }

        return data;
    }

    private Map<String, Object> serializeBodyRef(BodyReference ref) {
        Map<String, Object> data = new HashMap<>();
        data.put("path", ref.getPath());
        data.put("size", ref.getSize());
        if (ref.getContentType() != null) {
            data.put("contentType", ref.getContentType());
        }
        if (ref.getPreview() != null) {
            data.put("preview", ref.getPreview());
        }
        if (ref.getHash() != null) {
            data.put("hash", ref.getHash());
        }
        return data;
    }

    /**
     * Serializes headers as a list of [name, value] arrays.
     * This format preserves multiple values for the same header name.
     */
    private List<List<String>> serializeHeaders(List<HttpHeader> headers) {
        List<List<String>> result = new ArrayList<>();
        for (HttpHeader header : headers) {
            List<String> pair = new ArrayList<>(2);
            pair.add(header.getName());
            pair.add(header.getValue());
            result.add(pair);
        }
        return result;
    }
}
