package io.yamsergey.adt.debug.controller;

import io.yamsergey.adt.debug.DebugServerApplication;
import io.yamsergey.adt.debug.model.DebugEvent;
import io.yamsergey.adt.debug.service.EventService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for Vue.js UI and JSON API endpoints.
 */
@Controller
public class UiController {

    private final EventService eventService;

    public UiController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Redirect root to the Vue.js app.
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/index.html";
    }

    /**
     * Returns server status information.
     */
    @GetMapping(value = "/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("packageName", DebugServerApplication.getPackageName());
        status.put("eventCount", eventService.getEventCount());
        status.put("streamingConnected", eventService.isStreamingConnected());
        status.put("lastPullTime", eventService.getLastPullTime());
        return status;
    }

    /**
     * Returns network events, optionally filtered by timestamp.
     * @param since only return events with timestamp > since (0 = return all)
     */
    @GetMapping(value = "/api/network", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DebugEvent> getNetworkEvents(@RequestParam(defaultValue = "0") long since) {
        List<DebugEvent> events = eventService.getEventsByType("http");

        // Filter to only events newer than 'since'
        if (since > 0) {
            events.removeIf(e -> e.getTimestamp() <= since);
        }

        // Sort by timestamp descending (newest first)
        events.sort(Comparator.comparingLong(DebugEvent::getTimestamp).reversed());

        return events;
    }

    /**
     * Returns a single network event by ID.
     */
    @GetMapping(value = "/api/network/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DebugEvent getNetworkEvent(@PathVariable String id) {
        return eventService.getEvent(id);
    }

    // ========== Compose Inspector Proxy Endpoints ==========

    private static final String AGENT_BASE_URL = "http://localhost:8642";

    /**
     * Proxies the compose tree request to the sidekick agent.
     */
    @GetMapping(value = "/api/compose/tree", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> getComposeTree() {
        return proxyGetRequest(AGENT_BASE_URL + "/compose/tree", "application/json");
    }

    /**
     * Proxies the compose screenshot request to the sidekick agent.
     */
    @GetMapping(value = "/api/compose/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> getComposeScreenshot() {
        try {
            URL url = new URL(AGENT_BASE_URL + "/compose/screenshot");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (InputStream is = conn.getInputStream()) {
                    byte[] imageBytes = is.readAllBytes();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.IMAGE_PNG);
                    return new ResponseEntity<>(imageBytes, headers, HttpStatus.OK);
                }
            } else {
                return ResponseEntity.status(responseCode).build();
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(("Agent connection failed: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Proxies hit test request to the sidekick agent.
     */
    @GetMapping(value = "/api/compose/select", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> selectComposeElement(@RequestParam int x, @RequestParam int y) {
        return proxyGetRequest(AGENT_BASE_URL + "/compose/select?x=" + x + "&y=" + y, "application/json");
    }

    /**
     * Proxies hit test all request to the sidekick agent.
     */
    @GetMapping(value = "/api/compose/select-all", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> selectAllComposeElements(@RequestParam int x, @RequestParam int y) {
        return proxyGetRequest(AGENT_BASE_URL + "/compose/select-all?x=" + x + "&y=" + y, "application/json");
    }

    /**
     * Proxies element lookup request to the sidekick agent.
     */
    @GetMapping(value = "/api/compose/element/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> getComposeElement(@PathVariable String id) {
        return proxyGetRequest(AGENT_BASE_URL + "/compose/element/" + id, "application/json");
    }

    // ========== WebSocket Inspector Proxy Endpoints ==========

    /**
     * Proxies the WebSocket connections list request to the sidekick agent.
     */
    @GetMapping(value = "/api/websocket/connections", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> getWebSocketConnections() {
        return proxyGetRequest(AGENT_BASE_URL + "/websocket/connections", "application/json");
    }

    /**
     * Proxies a single WebSocket connection details request to the sidekick agent.
     */
    @GetMapping(value = "/api/websocket/connections/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> getWebSocketConnection(@PathVariable String id) {
        return proxyGetRequest(AGENT_BASE_URL + "/websocket/connections/" + id, "application/json");
    }

    /**
     * Proxies WebSocket messages request to the sidekick agent.
     */
    @GetMapping(value = "/api/websocket/connections/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> getWebSocketMessages(@PathVariable String id) {
        return proxyGetRequest(AGENT_BASE_URL + "/websocket/connections/" + id + "/messages", "application/json");
    }

    /**
     * Generic proxy helper for GET requests returning text/JSON.
     */
    private ResponseEntity<String> proxyGetRequest(String urlString, String contentType) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (InputStream is = conn.getInputStream()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    HttpHeaders headers = new HttpHeaders();
                    headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                    return new ResponseEntity<>(body, headers, HttpStatus.OK);
                }
            } else {
                try (InputStream es = conn.getErrorStream()) {
                    String error = es != null ? new String(es.readAllBytes(), StandardCharsets.UTF_8) : "Error";
                    return ResponseEntity.status(responseCode).body(error);
                }
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\": \"Agent connection failed: " + e.getMessage() + "\"}");
        }
    }
}
