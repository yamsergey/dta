package io.yamsergey.adt.debug.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing debug sessions.
 *
 * <p>In MCP coordination mode, this service tracks all sessions registered
 * by MCP servers. Each session has its own sidekick URL and selection state.
 *
 * <p>In standalone mode, a single "default" session is created from
 * command-line arguments.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final String DEFAULT_SESSION_ID = "default";

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile String activeSessionId;

    /**
     * Registers a new session.
     *
     * @param device       device serial number
     * @param packageName  package name
     * @param sidekickUrl  sidekick HTTP URL (e.g., "http://localhost:8642")
     * @return the created session
     */
    public Session registerSession(String device, String packageName, String sidekickUrl) {
        Session session = new Session(device, packageName, sidekickUrl);
        sessions.put(session.getId(), session);
        log.info("Registered session: {} -> {}", session.getId(), sidekickUrl);

        // Auto-activate first session
        if (activeSessionId == null) {
            activeSessionId = session.getId();
            log.info("Auto-activated session: {}", session.getId());
        }

        return session;
    }

    /**
     * Unregisters a session.
     *
     * @param sessionId session ID to remove
     * @return true if session was removed
     */
    public boolean unregisterSession(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("Unregistered session: {}", sessionId);

            // If active session was removed, switch to another
            if (sessionId.equals(activeSessionId)) {
                activeSessionId = sessions.isEmpty() ? null : sessions.keySet().iterator().next();
                log.info("Switched active session to: {}", activeSessionId);
            }
            return true;
        }
        return false;
    }

    /**
     * Gets a session by ID.
     */
    public Optional<Session> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Gets the currently active session.
     */
    public Optional<Session> getActiveSession() {
        if (activeSessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(activeSessionId));
    }

    /**
     * Sets the active session.
     *
     * @param sessionId session ID to activate
     * @return true if session exists and was activated
     */
    public boolean setActiveSession(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            activeSessionId = sessionId;
            log.info("Activated session: {}", sessionId);
            return true;
        }
        return false;
    }

    /**
     * Gets the active session ID.
     */
    public String getActiveSessionId() {
        return activeSessionId;
    }

    /**
     * Lists all registered sessions.
     */
    public List<Session> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Gets the sidekick URL for the active session.
     *
     * @return sidekick URL or null if no active session
     */
    public String getActiveSidekickUrl() {
        return getActiveSession()
                .map(Session::getSidekickUrl)
                .orElse(null);
    }

    /**
     * Sets the selected element for the active session.
     *
     * @param element the selected element data
     * @return true if selection was set
     */
    public boolean setSelectedElement(Map<String, Object> element) {
        return getActiveSession()
                .map(session -> {
                    session.setSelectedElement(element);
                    log.debug("Set selection for session {}: {}", session.getId(),
                            element != null ? element.get("composable") : "null");
                    return true;
                })
                .orElse(false);
    }

    /**
     * Gets the selected element for a session.
     *
     * @param sessionId session ID
     * @return selected element or empty if none
     */
    public Optional<Map<String, Object>> getSelectedElement(String sessionId) {
        return getSession(sessionId)
                .map(Session::getSelectedElement);
    }

    /**
     * Gets the selected element for the active session.
     */
    public Optional<Map<String, Object>> getActiveSelectedElement() {
        return getActiveSession()
                .map(Session::getSelectedElement);
    }

    /**
     * Clears the selection for the active session.
     */
    public void clearSelection() {
        getActiveSession().ifPresent(Session::clearSelection);
    }

    // =====================================================
    // Network Request Selection
    // =====================================================

    /**
     * Sets the selected network request for the active session.
     *
     * @param request the selected network request data
     * @return true if selection was set
     */
    public boolean setSelectedNetworkRequest(Map<String, Object> request) {
        return getActiveSession()
                .map(session -> {
                    session.setSelectedNetworkRequest(request);
                    log.debug("Set network selection for session {}: {}", session.getId(),
                            request != null ? request.get("url") : "null");
                    return true;
                })
                .orElse(false);
    }

    /**
     * Gets the selected network request for a session.
     *
     * @param sessionId session ID
     * @return selected network request or empty if none
     */
    public Optional<Map<String, Object>> getSelectedNetworkRequest(String sessionId) {
        return getSession(sessionId)
                .map(Session::getSelectedNetworkRequest);
    }

    /**
     * Gets the selected network request for the active session.
     */
    public Optional<Map<String, Object>> getActiveSelectedNetworkRequest() {
        return getActiveSession()
                .map(Session::getSelectedNetworkRequest);
    }

    /**
     * Clears the network selection for the active session.
     */
    public void clearNetworkSelection() {
        getActiveSession().ifPresent(Session::clearNetworkSelection);
    }

    /**
     * Creates a default session for standalone mode.
     *
     * @param sidekickUrl the sidekick URL to use
     */
    public void createDefaultSession(String sidekickUrl) {
        Session session = new Session("standalone", "standalone", sidekickUrl);
        sessions.put(DEFAULT_SESSION_ID, session);
        activeSessionId = DEFAULT_SESSION_ID;
        log.info("Created default standalone session: {}", sidekickUrl);
    }

    /**
     * Checks if running in MCP coordination mode (multiple sessions possible).
     */
    public boolean isMcpMode() {
        return !sessions.containsKey(DEFAULT_SESSION_ID);
    }

    /**
     * Returns true if there are any sessions registered.
     */
    public boolean hasSessions() {
        return !sessions.isEmpty();
    }
}
