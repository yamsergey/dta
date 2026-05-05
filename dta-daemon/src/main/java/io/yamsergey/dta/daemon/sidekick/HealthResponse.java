package io.yamsergey.dta.daemon.sidekick;

/**
 * Response from the sidekick /health endpoint.
 *
 * <p>{@code shimAttached} answers "are JVMTI hooks actually working?" —
 * a successfully connected sidekick whose shim isn't attached can serve
 * /health and a few state endpoints but every inspection capability
 * (network capture, layout tree, hooks, interceptor) is dead. The plugin
 * surfaces this in the connection-status banner so users don't waste
 * time wondering why network_requests is empty after a stale build.
 * Older sidekicks (pre this field) report shimAttached=true with
 * shimReason="unknown" — treated as "we don't know but probably ok"
 * for backward compatibility.</p>
 */
public record HealthResponse(
    String status,
    String name,
    String version,
    String socketName,
    String packageName,
    int sseClients,
    boolean shimAttached,
    String shimReason,
    String shimDetail
) {
    /**
     * Checks if the sidekick is healthy (status is "ok").
     */
    public boolean isHealthy() {
        return "ok".equals(status);
    }
}
