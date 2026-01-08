package io.yamsergey.adt.debug.controller;

import io.yamsergey.adt.debug.DebugServerApplication;
import io.yamsergey.adt.debug.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint (no authentication required).
 */
@RestController
public class HealthController {

    private final EventService eventService;

    public HealthController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "adt-debug-server");
        response.put("packageName", DebugServerApplication.getPackageName());
        response.put("eventCount", eventService.getEventCount());
        response.put("lastPullTime", eventService.getLastPullTime());
        response.put("streamingConnected", eventService.isStreamingConnected());
        return response;
    }
}
