package io.yamsergey.adt.debug.controller;

import io.yamsergey.adt.debug.model.DebugEvent;
import io.yamsergey.adt.debug.service.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for network (HTTP) events.
 */
@RestController
@RequestMapping("/network")
public class NetworkController {

    private final EventService eventService;

    public NetworkController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Lists all HTTP network events.
     */
    @GetMapping
    public Map<String, Object> listNetworkEvents(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {

        List<DebugEvent> events = eventService.getEventsByType("http");

        // Apply pagination
        int total = events.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(offset + limit, total);
        List<DebugEvent> page = events.subList(fromIndex, toIndex);

        Map<String, Object> response = new HashMap<>();
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("requests", page);
        return response;
    }

    /**
     * Gets a single HTTP request by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DebugEvent> getNetworkEvent(@PathVariable String id) {
        DebugEvent event = eventService.getEvent(id);
        if (event == null || !"http".equals(event.getType())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(event);
    }

    /**
     * Returns network statistics.
     */
    @GetMapping("/stats")
    public Map<String, Object> getNetworkStats() {
        List<DebugEvent> httpEvents = eventService.getEventsByType("http");

        int total = httpEvents.size();
        int successful = 0;
        int failed = 0;
        long totalDuration = 0;
        int withDuration = 0;

        for (DebugEvent event : httpEvents) {
            Map<String, Object> data = event.getData();
            if (data != null) {
                String status = (String) data.get("status");
                if ("COMPLETED".equals(status)) {
                    successful++;
                } else if ("FAILED".equals(status)) {
                    failed++;
                }

                Object durationObj = data.get("duration");
                if (durationObj instanceof Number) {
                    totalDuration += ((Number) durationObj).longValue();
                    withDuration++;
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("totalRequests", total);
        response.put("successful", successful);
        response.put("failed", failed);
        response.put("averageDuration", withDuration > 0 ? totalDuration / withDuration : 0);
        return response;
    }
}
