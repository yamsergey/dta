package io.yamsergey.dta.daemon.cdp;

import io.yamsergey.dta.daemon.sidekick.AdbShellLimiter;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Utility for discovering Chrome DevTools sockets on Android devices.
 *
 * <p>Chrome and WebView expose debugging sockets as Unix domain sockets
 * in the abstract namespace. This class helps discover and connect to them.</p>
 *
 * <h3>Socket Types</h3>
 * <ul>
 *   <li>{@code chrome_devtools_remote} - Main Chrome browser</li>
 *   <li>{@code webview_devtools_remote_*} - WebViews in apps</li>
 *   <li>{@code *_devtools_remote} - Chrome Custom Tabs and other Chrome instances</li>
 * </ul>
 */
public final class ChromeSocketDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ChromeSocketDiscovery.class);

    // Pattern to match DevTools sockets in /proc/net/unix output
    // Examples: @chrome_devtools_remote, @webview_devtools_remote_12345
    private static final Pattern DEVTOOLS_SOCKET_PATTERN =
        Pattern.compile("@((?:chrome_devtools_remote|webview_devtools_remote_\\d+|[\\w.]+_devtools_remote))");

    // TTL for socket discovery cache. Layout tree polls this on every request
    // via WebViewNetworkWatcher.refresh(); a 2s TTL coalesces burst polls
    // while still picking up a new WebView within one poll cycle.
    private static final long DISCOVERY_CACHE_TTL_MS = 2_000;
    private static final ConcurrentHashMap<String, CacheEntry> DISCOVERY_CACHE = new ConcurrentHashMap<>();
    // Per-device lock around cache refresh so a burst of concurrent callers
    // collapses to one ADB shell instead of N.
    private static final ConcurrentHashMap<String, Object> DISCOVERY_CACHE_LOCKS = new ConcurrentHashMap<>();

    // Cache entry carries either a socket list or an IOException. Negative
    // caching matters when adbd is wedged: otherwise each serialized caller
    // re-runs the 5s ADB timeout.
    private record CacheEntry(long timestampMs, List<String> sockets, IOException failure) {
        static CacheEntry ok(long ts, List<String> s) { return new CacheEntry(ts, s, null); }
        static CacheEntry err(long ts, IOException e) { return new CacheEntry(ts, null, e); }
    }

    private ChromeSocketDiscovery() {}

    /**
     * Finds all Chrome DevTools sockets on the device.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @return list of socket names (without @ prefix)
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static List<String> findDevToolsSockets(String deviceSerial)
            throws IOException, InterruptedException {

        // Cached discovery — plugin poll cadence (1–3s) would otherwise fire
        // one grep per request. 2s TTL coalesces bursts, still reacts fast
        // enough to a new WebView for the next poll cycle.
        String cacheKey = deviceSerial == null ? "default" : deviceSerial;
        CacheEntry cached = DISCOVERY_CACHE.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestampMs() <= DISCOVERY_CACHE_TTL_MS) {
            if (cached.failure() != null) throw cached.failure();
            return new ArrayList<>(cached.sockets());
        }

        // Miss: serialize concurrent callers so a burst collapses to one ADB call.
        Object lock = DISCOVERY_CACHE_LOCKS.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            cached = DISCOVERY_CACHE.get(cacheKey);
            now = System.currentTimeMillis();
            if (cached != null && now - cached.timestampMs() <= DISCOVERY_CACHE_TTL_MS) {
                if (cached.failure() != null) throw cached.failure();
                return new ArrayList<>(cached.sockets());
            }
            return doDiscover(deviceSerial, cacheKey);
        }
    }

    private static List<String> doDiscover(String deviceSerial, String cacheKey)
            throws IOException, InterruptedException {

        // Use grep on-device to avoid transferring the entire /proc/net/unix table.
        // cat on large socket tables can hang adb/adbd, especially when the app is closing.
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "grep", "devtools_remote", "/proc/net/unix");
        String cmdStr = String.join(" ", cmd);
        log.debug("ADB exec: {}", cmdStr);
        long start = System.currentTimeMillis();

        // Run under the per-device shell limiter so sidekick + WebView
        // discovery can't gang up on adbd's shell slots.
        String output;
        try {
            output = AdbShellLimiter.withPermit(deviceSerial, 3_000, () -> {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<String> future = executor.submit(() -> readStream(process.getInputStream()));
                    // 5s timeout: this command either returns in <1s or adbd is wedged —
                    // 10s just holds the slot hostage for longer.
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        long elapsedInner = System.currentTimeMillis() - start;
                        log.error("ADB timed out after {}ms: {}", elapsedInner, cmdStr);
                        throw new IOException("ADB command timed out: " + cmdStr);
                    }
                    return future.get(3, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    process.destroyForcibly();
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("Failed to read ADB output", cause);
                } catch (TimeoutException e) {
                    process.destroyForcibly();
                    long elapsedInner = System.currentTimeMillis() - start;
                    log.error("ADB read timed out after {}ms: {}", elapsedInner, cmdStr);
                    throw new IOException("Timed out reading ADB output: " + cmdStr);
                } finally {
                    executor.shutdownNow();
                }
            });
        } catch (IOException e) {
            // Negative-cache the failure so concurrent callers fail fast instead
            // of each re-running the 5s ADB timeout. Stamp at failure time.
            DISCOVERY_CACHE.put(cacheKey, CacheEntry.err(System.currentTimeMillis(), e));
            throw e;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            IOException wrapped = new IOException("DevTools socket discovery failed: " + e.getMessage(), e);
            DISCOVERY_CACHE.put(cacheKey, CacheEntry.err(System.currentTimeMillis(), wrapped));
            throw wrapped;
        }

        long elapsed = System.currentTimeMillis() - start;
        Set<String> sockets = new HashSet<>();
        Matcher matcher = DEVTOOLS_SOCKET_PATTERN.matcher(output);

        while (matcher.find()) {
            sockets.add(matcher.group(1));
        }

        log.debug("Found {} DevTools sockets in {}ms", sockets.size(), elapsed);
        List<String> result = List.copyOf(sockets);
        DISCOVERY_CACHE.put(cacheKey, CacheEntry.ok(System.currentTimeMillis(), result));
        return new ArrayList<>(result);
    }

    /**
     * Finds the main Chrome DevTools socket.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @return the socket name, or null if not found
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static String findChromeSocket(String deviceSerial)
            throws IOException, InterruptedException {

        List<String> sockets = findDevToolsSockets(deviceSerial);

        // Prefer chrome_devtools_remote
        for (String socket : sockets) {
            if ("chrome_devtools_remote".equals(socket)) {
                return socket;
            }
        }

        // Fall back to any chrome socket
        for (String socket : sockets) {
            if (socket.contains("chrome")) {
                return socket;
            }
        }

        return null;
    }

    /**
     * Finds WebView DevTools sockets.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @return list of WebView socket names
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static List<String> findWebViewSockets(String deviceSerial)
            throws IOException, InterruptedException {

        List<String> sockets = findDevToolsSockets(deviceSerial);
        List<String> webviewSockets = new ArrayList<>();

        for (String socket : sockets) {
            if (socket.startsWith("webview_devtools_remote_")) {
                webviewSockets.add(socket);
            }
        }

        return webviewSockets;
    }

    /**
     * Sets up port forwarding from a local TCP port to a Chrome DevTools socket.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @param localPort    the local TCP port to forward from
     * @param socketName   the socket name (without @ prefix)
     * @return true if forwarding was set up successfully
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static boolean setupPortForward(String deviceSerial, int localPort, String socketName)
            throws IOException, InterruptedException {

        List<String> cmd = buildAdbCommand(deviceSerial,
            "forward", "tcp:" + localPort, "localabstract:" + socketName);
        log.debug("Setting up port forward: tcp:{} -> localabstract:{}", localPort, socketName);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        // 5s: `adb forward` is a local operation against the ADB server; it
        // should be instant. A long timeout just masks a wedged ADB server.
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.error("Port forwarding timed out: tcp:{} -> {}", localPort, socketName);
            throw new IOException("ADB port forwarding timed out after 5 seconds");
        }
        boolean success = process.exitValue() == 0;
        if (!success) {
            log.error("Port forwarding failed (exit {}): tcp:{} -> {}", process.exitValue(), localPort, socketName);
        }
        return success;
    }

    /**
     * Removes port forwarding.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @param localPort    the local TCP port
     */
    public static void removePortForward(String deviceSerial, int localPort) {
        try {
            List<String> cmd = buildAdbCommand(deviceSerial,
                "forward", "--remove", "tcp:" + localPort);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            // Ignore cleanup errors
        }
    }

    /**
     * Finds an available local port for forwarding.
     *
     * @return an available port number
     * @throws IOException if no port is available
     */
    public static int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Sets up port forwarding and returns the local port.
     * Automatically finds an available port.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @param socketName   the socket name (without @ prefix)
     * @return the local port that was set up, or -1 if setup failed
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static int setupPortForwardAuto(String deviceSerial, String socketName)
            throws IOException, InterruptedException {

        int port = findAvailablePort();
        if (setupPortForward(deviceSerial, port, socketName)) {
            return port;
        }
        return -1;
    }

    private static List<String> buildAdbCommand(String deviceSerial, String... args) {
        List<String> cmd = new ArrayList<>();
        // Honor the SDK path that the plugin passes to SidekickConnectionManager.setAdbPath().
        // Falls back to "adb" when nothing's been set.
        cmd.add(SidekickConnectionManager.getAdbPath());
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            cmd.add("-s");
            cmd.add(deviceSerial);
        }
        for (String arg : args) {
            cmd.add(arg);
        }
        return cmd;
    }

    private static String readStream(java.io.InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}
