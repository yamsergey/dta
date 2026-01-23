package io.yamsergey.dta.sidekick.mock;

import io.yamsergey.dta.sidekick.network.HttpRequest;
import io.yamsergey.dta.sidekick.network.HttpResponse;
import io.yamsergey.dta.sidekick.network.HttpTransaction;
import io.yamsergey.dta.sidekick.network.NetworkInspector;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton manager for mock rules.
 *
 * <p>Handles CRUD operations for mock rules and matching logic for
 * intercepting HTTP requests and WebSocket messages.</p>
 */
public final class MockManager {

    private static final MockManager INSTANCE = new MockManager();

    private final MockConfig config;
    private final List<MockRule> rules;
    private final Map<String, MockRule> rulesById;

    private MockManager() {
        this.config = new MockConfig();
        this.rules = new CopyOnWriteArrayList<>();
        this.rulesById = new ConcurrentHashMap<>();
    }

    public static MockManager getInstance() {
        return INSTANCE;
    }

    // =========================================================================
    // Configuration
    // =========================================================================

    public MockConfig getConfig() {
        return config;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public boolean isHttpMockingActive() {
        return config.isHttpMockingActive();
    }

    public boolean isWebSocketMockingActive() {
        return config.isWebSocketMockingActive();
    }

    // =========================================================================
    // Rule CRUD Operations
    // =========================================================================

    /**
     * Adds a new mock rule.
     *
     * @param rule the rule to add
     * @return the added rule
     */
    public MockRule addRule(MockRule rule) {
        rules.add(rule);
        rulesById.put(rule.getId(), rule);
        return rule;
    }

    /**
     * Gets a rule by ID.
     *
     * @param id the rule ID
     * @return the rule, or null if not found
     */
    public MockRule getRule(String id) {
        return rulesById.get(id);
    }

    /**
     * Gets all rules.
     *
     * @return a copy of the rules list
     */
    public List<MockRule> getRules() {
        return new ArrayList<>(rules);
    }

    /**
     * Gets all HTTP mock rules.
     *
     * @return list of HTTP rules
     */
    public List<MockRule> getHttpRules() {
        List<MockRule> httpRules = new ArrayList<>();
        for (MockRule rule : rules) {
            if (rule.getType() == MockType.HTTP) {
                httpRules.add(rule);
            }
        }
        return httpRules;
    }

    /**
     * Gets all WebSocket mock rules.
     *
     * @return list of WebSocket rules
     */
    public List<MockRule> getWebSocketRules() {
        List<MockRule> wsRules = new ArrayList<>();
        for (MockRule rule : rules) {
            if (rule.getType() == MockType.WEBSOCKET) {
                wsRules.add(rule);
            }
        }
        return wsRules;
    }

    /**
     * Updates an existing rule.
     *
     * @param id the rule ID
     * @param updater a function that modifies the rule
     * @return the updated rule, or null if not found
     */
    public MockRule updateRule(String id, RuleUpdater updater) {
        MockRule rule = rulesById.get(id);
        if (rule != null) {
            updater.update(rule);
        }
        return rule;
    }

    /**
     * Deletes a rule by ID.
     *
     * @param id the rule ID
     * @return true if the rule was deleted
     */
    public boolean deleteRule(String id) {
        MockRule rule = rulesById.remove(id);
        if (rule != null) {
            rules.remove(rule);
            return true;
        }
        return false;
    }

    /**
     * Clears all rules.
     */
    public void clearRules() {
        rules.clear();
        rulesById.clear();
    }

    // =========================================================================
    // HTTP Matching
    // =========================================================================

    /**
     * Finds a matching HTTP mock rule for the given request.
     *
     * @param request the HTTP request
     * @return the matching rule, or null if no match
     */
    public MockRule findMatchingHttpRule(HttpRequest request) {
        if (!isHttpMockingActive() || request == null) {
            return null;
        }

        String url = request.getUrl();
        String method = request.getMethod();

        for (MockRule rule : rules) {
            if (rule.getType() != MockType.HTTP) {
                continue;
            }
            if (!rule.isEnabled()) {
                continue;
            }
            if (!rule.matchesUrl(url)) {
                continue;
            }
            if (!rule.matchesMethod(method)) {
                continue;
            }
            return rule;
        }
        return null;
    }

    // =========================================================================
    // WebSocket Matching
    // =========================================================================

    /**
     * Finds a matching WebSocket mock rule for the given message.
     *
     * @param url the WebSocket URL
     * @param direction the message direction
     * @param payload the message payload (for text messages)
     * @return the matching rule, or null if no match
     */
    public MockRule findMatchingWebSocketRule(String url, WebSocketMessage.Direction direction, String payload) {
        if (!isWebSocketMockingActive()) {
            return null;
        }

        for (MockRule rule : rules) {
            if (rule.getType() != MockType.WEBSOCKET) {
                continue;
            }
            if (!rule.isEnabled()) {
                continue;
            }
            if (!rule.matchesUrl(url)) {
                continue;
            }
            if (!rule.matchesDirection(direction)) {
                continue;
            }
            if (!rule.matchesContent(payload)) {
                continue;
            }
            return rule;
        }
        return null;
    }

    /**
     * Finds a matching WebSocket mock rule for binary messages.
     *
     * @param url the WebSocket URL
     * @param direction the message direction
     * @return the matching rule, or null if no match
     */
    public MockRule findMatchingWebSocketRule(String url, WebSocketMessage.Direction direction) {
        return findMatchingWebSocketRule(url, direction, null);
    }

    // =========================================================================
    // Factory Methods for Creating Rules from Captured Data
    // =========================================================================

    /**
     * Creates a mock rule from a captured HTTP transaction.
     *
     * @param transactionId the ID of the captured transaction
     * @return a builder pre-filled with the transaction's response, or null if not found
     */
    public MockRule.Builder createRuleFromTransaction(String transactionId) {
        HttpTransaction tx = NetworkInspector.getTransaction(transactionId);
        if (tx == null || tx.getResponse() == null) {
            return null;
        }

        HttpRequest request = tx.getRequest();
        HttpResponse response = tx.getResponse();

        // Build mock response from captured response
        MockHttpResponse.Builder mockResponseBuilder = MockHttpResponse.builder()
                .statusCode(response.getStatusCode())
                .statusMessage(response.getStatusMessage())
                .contentType(response.getContentType())
                .capturedTransactionId(transactionId);

        // Copy headers
        if (response.getHeaders() != null) {
            for (var header : response.getHeaders()) {
                mockResponseBuilder.addHeader(header.getName(), header.getValue());
            }
        }

        // Copy body
        if (response.getBody() != null) {
            mockResponseBuilder.body(response.getBody());
        }

        // Create rule builder
        String name = request.getMethod() + " " + extractPath(request.getUrl());
        return MockRule.builder()
                .type(MockType.HTTP)
                .name(name)
                .urlPattern(escapeRegex(request.getUrl()))
                .method(request.getMethod())
                .mockResponse(mockResponseBuilder.build());
    }

    /**
     * Creates a mock rule from a captured WebSocket message.
     *
     * @param messageId the ID of the captured message
     * @return a mock rule builder, or null if message not found
     */
    public MockRule.Builder createRuleFromMessage(String messageId) {
        WebSocketMessage msg = WebSocketInspector.getMessage(messageId);
        if (msg == null) {
            return null;
        }

        // Get the connection to get the URL
        WebSocketConnection conn = WebSocketInspector.getConnection(msg.getConnectionId());
        if (conn == null) {
            return null;
        }

        // Build mock message from captured message
        MockWebSocketMessage.Builder mockMessageBuilder;
        if (msg.isText()) {
            mockMessageBuilder = MockWebSocketMessage.textMessage(msg.getTextPayload());
        } else {
            mockMessageBuilder = MockWebSocketMessage.binaryMessage(msg.getBinaryPayload());
        }
        mockMessageBuilder.capturedMessageId(messageId);

        // Determine direction - mock the same direction as captured
        MockDirection direction = msg.isSent() ? MockDirection.SENT : MockDirection.RECEIVED;

        // Create rule name
        String payload = msg.isText() ? truncate(msg.getTextPayload(), 30) : "[binary]";
        String name = (msg.isSent() ? "SEND" : "RECV") + " " + payload;

        return MockRule.builder()
                .type(MockType.WEBSOCKET)
                .name(name)
                .urlPattern(escapeRegex(conn.getUrl()))
                .direction(direction)
                .mockMessage(mockMessageBuilder.build());
    }

    /**
     * Creates a mock rule from a WebSocket message pattern.
     *
     * @param urlPattern the WebSocket URL pattern
     * @param direction the direction to mock
     * @param textPayload the mock text payload
     * @return a mock rule builder
     */
    public MockRule.Builder createWebSocketRule(String urlPattern, MockDirection direction, String textPayload) {
        return MockRule.builder()
                .type(MockType.WEBSOCKET)
                .name("WebSocket: " + urlPattern)
                .urlPattern(urlPattern)
                .direction(direction)
                .mockMessage(MockWebSocketMessage.textMessage(textPayload).build());
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private String extractPath(String url) {
        if (url == null) {
            return "";
        }
        try {
            int pathStart = url.indexOf("://");
            if (pathStart >= 0) {
                pathStart = url.indexOf('/', pathStart + 3);
                if (pathStart >= 0) {
                    int queryStart = url.indexOf('?', pathStart);
                    if (queryStart >= 0) {
                        return url.substring(pathStart, queryStart);
                    }
                    return url.substring(pathStart);
                }
            }
            return url;
        } catch (Exception e) {
            return url;
        }
    }

    private String escapeRegex(String input) {
        if (input == null) {
            return null;
        }
        // Escape special regex characters for exact matching
        return input.replaceAll("([\\\\\\^\\$\\.\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}])", "\\\\$1");
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }

    /**
     * Functional interface for updating rules.
     */
    @FunctionalInterface
    public interface RuleUpdater {
        void update(MockRule rule);
    }
}
