package io.yamsergey.adt.debug.controller;

import io.yamsergey.adt.debug.DebugServerApplication;
import io.yamsergey.adt.debug.model.DebugEvent;
import io.yamsergey.adt.debug.service.EventService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

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
}
