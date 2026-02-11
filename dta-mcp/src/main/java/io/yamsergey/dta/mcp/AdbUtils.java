package io.yamsergey.dta.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Utility class for ADB operations.
 */
public class AdbUtils {

    private static final Logger log = LoggerFactory.getLogger(AdbUtils.class);
    private static final String ADB = "adb";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * Represents a connected Android device.
     */
    public record Device(String serial, String state, String model, String product) {}

    /**
     * Represents a sidekick socket discovered on a device.
     */
    public record SidekickSocket(String socketName, String packageName) {}

    /**
     * Lists connected devices.
     */
    public static List<Device> listDevices() throws IOException, InterruptedException {
        String output = runAdb(null, "devices", "-l");

        List<Device> devices = new ArrayList<>();
        Pattern pattern = Pattern.compile("^(\\S+)\\s+(device|offline|unauthorized)\\s*(.*)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(output);

        while (matcher.find()) {
            String serial = matcher.group(1);
            String state = matcher.group(2);
            String props = matcher.group(3);

            String model = extractProp(props, "model");
            String product = extractProp(props, "product");

            devices.add(new Device(serial, state, model, product));
        }

        return devices;
    }

    /**
     * Discovers sidekick sockets on a device.
     */
    public static List<SidekickSocket> discoverSidekickSockets(String deviceSerial) throws IOException, InterruptedException {
        String output = runAdb(deviceSerial, "shell", "cat", "/proc/net/unix");

        List<SidekickSocket> sockets = new ArrayList<>();
        Pattern pattern = Pattern.compile("@(dta_sidekick_([\\w.]+))");
        Matcher matcher = pattern.matcher(output);

        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String socketName = matcher.group(1);
            String packageName = matcher.group(2);
            if (seen.add(socketName)) {
                sockets.add(new SidekickSocket(socketName, packageName));
            }
        }

        return sockets;
    }

    /**
     * Captures a screenshot from the device.
     */
    public static byte[] captureScreenshot(String deviceSerial) throws IOException, InterruptedException {
        return runAdbBytes(deviceSerial, "exec-out", "screencap", "-p");
    }

    /**
     * Taps at screen coordinates.
     */
    public static boolean tap(String deviceSerial, int x, int y) throws IOException, InterruptedException {
        runAdb(deviceSerial, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
        return true;
    }

    /**
     * Swipes from one point to another.
     */
    public static boolean swipe(String deviceSerial, int x1, int y1, int x2, int y2, int durationMs) throws IOException, InterruptedException {
        runAdb(deviceSerial, "shell", "input", "swipe",
            String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
        return true;
    }

    /**
     * Inputs text.
     */
    public static boolean inputText(String deviceSerial, String text) throws IOException, InterruptedException {
        // Escape special characters for shell
        String escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'");
        runAdb(deviceSerial, "shell", "input", "text", escaped);
        return true;
    }

    /**
     * Presses a key.
     */
    public static boolean pressKey(String deviceSerial, String keyCode) throws IOException, InterruptedException {
        // Map common key names to keycodes
        String code = switch (keyCode.toUpperCase()) {
            case "BACK" -> "4";
            case "HOME" -> "3";
            case "MENU" -> "82";
            case "ENTER" -> "66";
            case "TAB" -> "61";
            case "DEL", "DELETE" -> "67";
            case "SPACE" -> "62";
            default -> keyCode;
        };

        runAdb(deviceSerial, "shell", "input", "keyevent", code);
        return true;
    }

    /**
     * Sets up port forwarding to a Unix domain socket.
     */
    public static boolean setupPortForward(String deviceSerial, int localPort, String socketName) throws IOException, InterruptedException {
        runAdb(deviceSerial, "forward", "tcp:" + localPort, "localabstract:" + socketName);
        return true;
    }

    /**
     * Sets up port forwarding with automatic port allocation (tcp:0).
     * Returns the port assigned by ADB.
     */
    public static int setupPortForwardAuto(String deviceSerial, String socketName) throws IOException, InterruptedException {
        String output = runAdb(deviceSerial, "forward", "tcp:0", "localabstract:" + socketName);
        return Integer.parseInt(output.trim());
    }

    /**
     * Sets up port forwarding to Chrome DevTools Protocol socket.
     */
    public static boolean setupCdpPortForward(String deviceSerial, int localPort) throws IOException, InterruptedException {
        return setupPortForward(deviceSerial, localPort, "chrome_devtools_remote");
    }

    /**
     * Removes port forwarding.
     */
    public static void removePortForward(String deviceSerial, int localPort) {
        try {
            runAdb(deviceSerial, "forward", "--remove", "tcp:" + localPort);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    private static List<String> buildAdbCommand(String deviceSerial, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ADB);
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            cmd.add("-s");
            cmd.add(deviceSerial);
        }
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    /**
     * Runs an ADB command, drains all output, and waits with a timeout.
     * Returns stdout as a String. Throws on timeout or non-zero exit.
     */
    private static String runAdb(String deviceSerial, String... args) throws IOException, InterruptedException {
        return new String(runAdbBytes(deviceSerial, args), StandardCharsets.UTF_8);
    }

    /**
     * Runs an ADB command, drains all output, and waits with a timeout.
     * Returns stdout as raw bytes. Throws on timeout.
     */
    private static byte[] runAdbBytes(String deviceSerial, String... args) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(deviceSerial, args);
        String cmdStr = String.join(" ", cmd);
        log.debug("ADB exec: {}", cmdStr);
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> future = executor.submit(
                () -> process.getInputStream().readAllBytes()
            );
            if (!process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                log.error("ADB timed out after {}ms: {}", elapsed, cmdStr);
                throw new IOException("ADB command timed out: " + cmdStr);
            }
            byte[] data = future.get(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            log.debug("ADB done in {}ms ({} bytes): {}", elapsed, data.length, cmdStr);
            return data;
        } catch (ExecutionException e) {
            process.destroyForcibly();
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Failed to read ADB output", cause);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw new IOException("Timed out reading ADB output: " + cmdStr);
        } catch (IOException | InterruptedException e) {
            process.destroyForcibly();
            long elapsed = System.currentTimeMillis() - start;
            log.error("ADB failed after {}ms: {} - {}", elapsed, cmdStr, e.getMessage());
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String extractProp(String props, String name) {
        Pattern p = Pattern.compile(name + ":(\\S+)");
        Matcher m = p.matcher(props);
        return m.find() ? m.group(1) : null;
    }
}
