package io.yamsergey.dta.daemon.sidekick;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps simultaneous {@code adb shell ...} processes per device so we don't
 * exhaust adbd's shell slots. A wedged adbd takes the whole device's ADB
 * surface down with it — every subsequent shell sits on the 30s timeout —
 * so the real fix is to never issue more than a couple of concurrent shells
 * against one device.
 *
 * <p>Two permits per device is enough for our fan-out (sidekick socket
 * discovery + WebView socket discovery can overlap briefly; anything beyond
 * that is spam). A null device serial maps to a shared default bucket so
 * plugin/MCP requests that omit the device don't bypass the limit.</p>
 */
public final class AdbShellLimiter {

    private static final int PERMITS_PER_DEVICE = 2;
    private static final ConcurrentHashMap<String, Semaphore> SEMAPHORES = new ConcurrentHashMap<>();

    private AdbShellLimiter() {}

    /**
     * Runs {@code body} while holding one permit for {@code deviceSerial}.
     * If a permit can't be acquired within {@code timeoutMs}, throws
     * {@link IOException} — callers should treat this identically to an
     * ADB timeout.
     */
    public static <T> T withPermit(String deviceSerial, long timeoutMs, Callable<T> body) throws Exception {
        String key = deviceSerial == null ? "default" : deviceSerial;
        Semaphore sem = SEMAPHORES.computeIfAbsent(key, k -> new Semaphore(PERMITS_PER_DEVICE, true));
        if (!sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("adb shell queue busy for " + key
                + " (no permit after " + timeoutMs + "ms)");
        }
        try {
            return body.call();
        } finally {
            sem.release();
        }
    }
}
