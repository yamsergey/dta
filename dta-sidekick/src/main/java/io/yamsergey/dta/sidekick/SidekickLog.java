package io.yamsergey.dta.sidekick;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logging utility for Sidekick.
 *
 * <p>By default, debug logging is disabled to avoid polluting logcat.
 * Enable it via {@link SidekickConfig.Builder#enableDebugLogging()}.</p>
 *
 * <p>Error logging (Log.e) is always enabled regardless of the debug setting.</p>
 *
 * <p>File logging can be enabled via {@link SidekickConfig.Builder#enableFileLogging()}.
 * Logs are written to {@code <cacheDir>/sidekick.log} and can be pulled with:
 * {@code dta-cli inspect log-pull --package com.example.app}</p>
 */
public final class SidekickLog {

    public static final String LOG_FILE_NAME = "sidekick.log";

    private static volatile boolean debugEnabled = false;
    private static volatile PrintWriter fileWriter = null;
    private static final SimpleDateFormat DATE_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private SidekickLog() {}

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Starts writing all log messages to a file.
     * Debug logging is automatically enabled when file logging is active.
     */
    public static synchronized void startFileLogging(File file) {
        stopFileLogging();
        try {
            fileWriter = new PrintWriter(new BufferedWriter(new FileWriter(file, false)), true);
            debugEnabled = true;
            writeToFile("I", "SidekickLog", "File logging started: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e("SidekickLog", "Failed to start file logging", e);
            fileWriter = null;
        }
    }

    /**
     * Stops file logging.
     */
    public static synchronized void stopFileLogging() {
        PrintWriter pw = fileWriter;
        if (pw != null) {
            writeToFile("I", "SidekickLog", "File logging stopped");
            pw.close();
            fileWriter = null;
        }
    }

    public static void d(String tag, String msg) {
        if (debugEnabled) Log.d(tag, msg);
        writeToFile("D", tag, msg);
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (debugEnabled) Log.d(tag, msg, tr);
        writeToFile("D", tag, msg, tr);
    }

    public static void i(String tag, String msg) {
        if (debugEnabled) Log.i(tag, msg);
        writeToFile("I", tag, msg);
    }

    public static void i(String tag, String msg, Throwable tr) {
        if (debugEnabled) Log.i(tag, msg, tr);
        writeToFile("I", tag, msg, tr);
    }

    public static void w(String tag, String msg) {
        if (debugEnabled) Log.w(tag, msg);
        writeToFile("W", tag, msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (debugEnabled) Log.w(tag, msg, tr);
        writeToFile("W", tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        writeToFile("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        writeToFile("E", tag, msg, tr);
    }

    public static void v(String tag, String msg) {
        if (debugEnabled) Log.v(tag, msg);
        writeToFile("V", tag, msg);
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (debugEnabled) Log.v(tag, msg, tr);
        writeToFile("V", tag, msg, tr);
    }

    private static void writeToFile(String level, String tag, String msg) {
        PrintWriter pw = fileWriter;
        if (pw != null) {
            pw.println(DATE_FORMAT.format(new Date()) + " " + level + "/" + tag + ": " + msg);
        }
    }

    private static void writeToFile(String level, String tag, String msg, Throwable tr) {
        PrintWriter pw = fileWriter;
        if (pw != null) {
            pw.println(DATE_FORMAT.format(new Date()) + " " + level + "/" + tag + ": " + msg);
            if (tr != null) tr.printStackTrace(pw);
        }
    }
}
