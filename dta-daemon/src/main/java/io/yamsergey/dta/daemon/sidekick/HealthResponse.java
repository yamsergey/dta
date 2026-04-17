package io.yamsergey.dta.daemon.sidekick;

/**
 * Response from the sidekick /health endpoint.
 */
public record HealthResponse(
    String status,
    String name,
    String version,
    String socketName,
    String packageName,
    int sseClients
) {
    /**
     * Checks if the sidekick is healthy (status is "ok").
     */
    public boolean isHealthy() {
        return "ok".equals(status);
    }
}
