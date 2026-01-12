package io.yamsergey.adt.debug.controller;

import io.yamsergey.adt.debug.session.Session;
import io.yamsergey.adt.debug.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for session management.
 *
 * <p>Used by MCP servers to register/unregister sessions and by
 * the web UI to switch between sessions and manage selection state.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Lists all registered sessions.
     *
     * <p>GET /api/sessions
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listSessions() {
        Map<String, Object> response = new HashMap<>();
        response.put("sessions", sessionService.listSessions());
        response.put("activeSessionId", sessionService.getActiveSessionId());
        response.put("mcpMode", sessionService.isMcpMode());
        return response;
    }

    /**
     * Registers a new session.
     *
     * <p>POST /api/sessions
     * <pre>
     * {
     *   "device": "emulator-5554",
     *   "packageName": "com.example.app",
     *   "sidekickUrl": "http://localhost:8642"
     * }
     * </pre>
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Session> registerSession(@RequestBody Map<String, String> request) {
        String device = request.get("device");
        String packageName = request.get("packageName");
        String sidekickUrl = request.get("sidekickUrl");

        if (device == null || packageName == null || sidekickUrl == null) {
            return ResponseEntity.badRequest().build();
        }

        Session session = sessionService.registerSession(device, packageName, sidekickUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * Gets a specific session by ID.
     *
     * <p>GET /api/sessions/{id}
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Session> getSession(@PathVariable String id) {
        return sessionService.getSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Unregisters a session.
     *
     * <p>DELETE /api/sessions/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unregisterSession(@PathVariable String id) {
        if (sessionService.unregisterSession(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Sets the active session.
     *
     * <p>POST /api/sessions/{id}/activate
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activateSession(@PathVariable String id) {
        if (sessionService.setActiveSession(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Gets the currently active session.
     *
     * <p>GET /api/sessions/active
     */
    @GetMapping(value = "/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Session> getActiveSession() {
        return sessionService.getActiveSession()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Gets the selected element for a session.
     *
     * <p>GET /api/sessions/{id}/selected
     */
    @GetMapping(value = "/{id}/selected", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getSelectedElement(@PathVariable String id) {
        return sessionService.getSelectedElement(id)
                .map(element -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("element", element);
                    response.put("sessionId", id);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("element", null);
                    response.put("sessionId", id);
                    return ResponseEntity.ok(response);
                });
    }

    /**
     * Sets the selected element for the active session.
     *
     * <p>POST /api/sessions/active/selected
     * <pre>
     * {
     *   "element": { ... element data ... }
     * }
     * </pre>
     */
    @PostMapping(value = "/active/selected", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> setSelectedElement(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> element = (Map<String, Object>) request.get("element");

        if (sessionService.setSelectedElement(element)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .build();
    }

    /**
     * Clears the selection for the active session.
     *
     * <p>DELETE /api/sessions/active/selected
     */
    @DeleteMapping("/active/selected")
    public ResponseEntity<Void> clearSelection() {
        sessionService.clearSelection();
        return ResponseEntity.noContent().build();
    }

    // =====================================================
    // Network Request Selection
    // =====================================================

    /**
     * Gets the selected network request for a session.
     *
     * <p>GET /api/sessions/{id}/selected-network
     */
    @GetMapping(value = "/{id}/selected-network", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getSelectedNetworkRequest(@PathVariable String id) {
        return sessionService.getSelectedNetworkRequest(id)
                .map(request -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("request", request);
                    response.put("sessionId", id);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("request", null);
                    response.put("sessionId", id);
                    return ResponseEntity.ok(response);
                });
    }

    /**
     * Sets the selected network request for the active session.
     *
     * <p>POST /api/sessions/active/selected-network
     * <pre>
     * {
     *   "request": { ... network request data ... }
     * }
     * </pre>
     */
    @PostMapping(value = "/active/selected-network", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> setSelectedNetworkRequest(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) body.get("request");

        if (sessionService.setSelectedNetworkRequest(request)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .build();
    }

    /**
     * Clears the network selection for the active session.
     *
     * <p>DELETE /api/sessions/active/selected-network
     */
    @DeleteMapping("/active/selected-network")
    public ResponseEntity<Void> clearNetworkSelection() {
        sessionService.clearNetworkSelection();
        return ResponseEntity.noContent().build();
    }
}
