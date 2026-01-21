package io.yamsergey.dta.sidekick;

import android.util.Log;

/**
 * Logging utility for Sidekick.
 *
 * <p>By default, debug logging is disabled to avoid polluting logcat.
 * Enable it via {@link SidekickConfig.Builder#enableDebugLogging()}.</p>
 *
 * <p>Error logging (Log.e) is always enabled regardless of the debug setting.</p>
 */
public final class SidekickLog {

    private static volatile boolean debugEnabled = false;

    private SidekickLog() {}

    /**
     * Sets whether debug logging is enabled.
     * Called internally by Sidekick configuration.
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /**
     * Returns whether debug logging is enabled.
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Logs a debug message if debug logging is enabled.
     */
    public static void d(String tag, String msg) {
        if (debugEnabled) {
            Log.d(tag, msg);
        }
    }

    /**
     * Logs a debug message with throwable if debug logging is enabled.
     */
    public static void d(String tag, String msg, Throwable tr) {
        if (debugEnabled) {
            Log.d(tag, msg, tr);
        }
    }

    /**
     * Logs an info message if debug logging is enabled.
     */
    public static void i(String tag, String msg) {
        if (debugEnabled) {
            Log.i(tag, msg);
        }
    }

    /**
     * Logs an info message with throwable if debug logging is enabled.
     */
    public static void i(String tag, String msg, Throwable tr) {
        if (debugEnabled) {
            Log.i(tag, msg, tr);
        }
    }

    /**
     * Logs a warning message if debug logging is enabled.
     */
    public static void w(String tag, String msg) {
        if (debugEnabled) {
            Log.w(tag, msg);
        }
    }

    /**
     * Logs a warning message with throwable if debug logging is enabled.
     */
    public static void w(String tag, String msg, Throwable tr) {
        if (debugEnabled) {
            Log.w(tag, msg, tr);
        }
    }

    /**
     * Logs an error message. Always enabled regardless of debug setting.
     */
    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    /**
     * Logs an error message with throwable. Always enabled regardless of debug setting.
     */
    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
    }

    /**
     * Logs a verbose message if debug logging is enabled.
     */
    public static void v(String tag, String msg) {
        if (debugEnabled) {
            Log.v(tag, msg);
        }
    }

    /**
     * Logs a verbose message with throwable if debug logging is enabled.
     */
    public static void v(String tag, String msg, Throwable tr) {
        if (debugEnabled) {
            Log.v(tag, msg, tr);
        }
    }
}
