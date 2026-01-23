package io.yamsergey.dta.sidekick.mock;

/**
 * Direction for WebSocket message mocking.
 */
public enum MockDirection {
    /** Mock only sent messages */
    SENT,
    /** Mock only received messages */
    RECEIVED,
    /** Mock both sent and received messages */
    BOTH
}
