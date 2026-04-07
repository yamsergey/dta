package io.yamsergey.dta.daemon;

/**
 * Exception thrown when communication with the DTA daemon fails.
 */
public class DaemonException extends RuntimeException {

    public DaemonException(String message) {
        super(message);
    }

    public DaemonException(String message, Throwable cause) {
        super(message, cause);
    }
}
