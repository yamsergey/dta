package io.yamsergey.dta.sidekick.mock;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Represents a mock rule that defines how to intercept and modify requests/messages.
 */
public class MockRule {

    private final String id;
    private volatile String name;
    private final MockType type;
    private volatile boolean enabled;
    private volatile String urlPattern;
    private volatile Pattern compiledPattern;
    private volatile String contentPattern; // For matching message/body content
    private volatile Pattern compiledContentPattern;
    private volatile String method; // HTTP method (null = any)
    private volatile MockDirection direction; // For WebSocket
    private volatile MockHttpResponse mockResponse;
    private volatile MockWebSocketMessage mockMessage;
    private volatile int delayMs;
    private final AtomicInteger matchCount;
    private final long createdAt;

    private MockRule(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.name = builder.name;
        this.type = builder.type;
        this.enabled = builder.enabled;
        this.urlPattern = builder.urlPattern;
        this.compiledPattern = compilePattern(builder.urlPattern);
        this.contentPattern = builder.contentPattern;
        this.compiledContentPattern = compilePattern(builder.contentPattern);
        this.method = builder.method;
        this.direction = builder.direction;
        this.mockResponse = builder.mockResponse;
        this.mockMessage = builder.mockMessage;
        this.delayMs = builder.delayMs;
        this.matchCount = new AtomicInteger(0);
        this.createdAt = System.currentTimeMillis();
    }

    private Pattern compilePattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (Exception e) {
            return null;
        }
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MockType getType() {
        return type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public String getContentPattern() {
        return contentPattern;
    }

    public String getMethod() {
        return method;
    }

    public MockDirection getDirection() {
        return direction;
    }

    public MockHttpResponse getMockResponse() {
        return mockResponse;
    }

    public MockWebSocketMessage getMockMessage() {
        return mockMessage;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public int getMatchCount() {
        return matchCount.get();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    // Setters
    public MockRule setName(String name) {
        this.name = name;
        return this;
    }

    public MockRule setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public MockRule setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
        this.compiledPattern = compilePattern(urlPattern);
        return this;
    }

    public MockRule setContentPattern(String contentPattern) {
        this.contentPattern = contentPattern;
        this.compiledContentPattern = compilePattern(contentPattern);
        return this;
    }

    public MockRule setMethod(String method) {
        this.method = method;
        return this;
    }

    public MockRule setDirection(MockDirection direction) {
        this.direction = direction;
        return this;
    }

    public MockRule setMockResponse(MockHttpResponse mockResponse) {
        this.mockResponse = mockResponse;
        return this;
    }

    public MockRule setMockMessage(MockWebSocketMessage mockMessage) {
        this.mockMessage = mockMessage;
        return this;
    }

    public MockRule setDelayMs(int delayMs) {
        this.delayMs = delayMs;
        return this;
    }

    /**
     * Increments the match count and returns the new value.
     */
    public int incrementMatchCount() {
        return matchCount.incrementAndGet();
    }

    /**
     * Checks if this rule matches the given URL.
     */
    public boolean matchesUrl(String url) {
        if (url == null) {
            return false;
        }
        if (compiledPattern != null) {
            return compiledPattern.matcher(url).find();
        }
        // If no pattern, match all
        return true;
    }

    /**
     * Checks if this rule matches the given HTTP method.
     */
    public boolean matchesMethod(String requestMethod) {
        if (method == null || method.isEmpty()) {
            return true; // Match any method
        }
        return method.equalsIgnoreCase(requestMethod);
    }

    /**
     * Checks if this rule matches the given WebSocket direction.
     */
    public boolean matchesDirection(io.yamsergey.dta.sidekick.network.WebSocketMessage.Direction msgDirection) {
        if (direction == null || direction == MockDirection.BOTH) {
            return true;
        }
        if (msgDirection == io.yamsergey.dta.sidekick.network.WebSocketMessage.Direction.SENT) {
            return direction == MockDirection.SENT;
        }
        if (msgDirection == io.yamsergey.dta.sidekick.network.WebSocketMessage.Direction.RECEIVED) {
            return direction == MockDirection.RECEIVED;
        }
        return false;
    }

    /**
     * Checks if this rule matches the given content (message body or WebSocket payload).
     */
    public boolean matchesContent(String content) {
        if (content == null) {
            return compiledContentPattern == null;
        }
        if (compiledContentPattern != null) {
            return compiledContentPattern.matcher(content).find();
        }
        // If no pattern, match all
        return true;
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private MockType type;
        private boolean enabled = true;
        private String urlPattern;
        private String contentPattern;
        private String method;
        private MockDirection direction;
        private MockHttpResponse mockResponse;
        private MockWebSocketMessage mockMessage;
        private int delayMs;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(MockType type) {
            this.type = type;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder urlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
            return this;
        }

        public Builder contentPattern(String contentPattern) {
            this.contentPattern = contentPattern;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder direction(MockDirection direction) {
            this.direction = direction;
            return this;
        }

        public Builder mockResponse(MockHttpResponse mockResponse) {
            this.mockResponse = mockResponse;
            return this;
        }

        public Builder mockMessage(MockWebSocketMessage mockMessage) {
            this.mockMessage = mockMessage;
            return this;
        }

        public Builder delayMs(int delayMs) {
            this.delayMs = delayMs;
            return this;
        }

        public MockRule build() {
            if (type == null) {
                throw new IllegalStateException("MockRule type is required");
            }
            return new MockRule(this);
        }
    }
}
