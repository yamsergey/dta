package io.yamsergey.adt.debug.controller;

import io.yamsergey.adt.debug.model.DebugEvent;
import io.yamsergey.adt.debug.service.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for debug events.
 */
@RestController
@RequestMapping("/events")
public class EventsController {

    private final EventService eventService;

    public EventsController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Lists all events, optionally filtered by type.
     */
    @GetMapping
    public Map<String, Object> listEvents(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {

        List<DebugEvent> events = type != null
                ? eventService.getEventsByType(type)
                : eventService.getAllEvents();

        // Apply pagination
        int total = events.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(offset + limit, total);
        List<DebugEvent> page = events.subList(fromIndex, toIndex);

        Map<String, Object> response = new HashMap<>();
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("events", page);
        return response;
    }

    /**
     * Gets a single event by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DebugEvent> getEvent(@PathVariable String id) {
        DebugEvent event = eventService.getEvent(id);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(event);
    }

    /**
     * Lists available event types.
     */
    @GetMapping("/types")
    public Map<String, Object> getEventTypes() {
        Map<String, Object> response = new HashMap<>();
        response.put("types", List.of("http", "websocket", "plain", "custom"));
        return response;
    }

    /**
     * Forces an immediate pull of events from the device.
     */
    @PostMapping("/pull")
    public Map<String, Object> pullEvents() {
        int countBefore = eventService.getEventCount();
        eventService.forcePull();
        int countAfter = eventService.getEventCount();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("newEvents", countAfter - countBefore);
        response.put("totalEvents", countAfter);
        return response;
    }

    /**
     * Clears all events.
     */
    @DeleteMapping
    public Map<String, Object> clearEvents() {
        eventService.clearEvents();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "All events cleared");
        return response;
    }
}
