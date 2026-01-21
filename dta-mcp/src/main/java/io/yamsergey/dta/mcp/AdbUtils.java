package io.yamsergey.dta.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Utility class for ADB operations.
 */
public class AdbUtils {

    private static final String ADB = "adb";

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
        ProcessBuilder pb = new ProcessBuilder(ADB, "devices", "-l");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        process.waitFor();

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
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "cat", "/proc/net/unix");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        process.waitFor();

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
        List<String> cmd = buildAdbCommand(deviceSerial, "exec-out", "screencap", "-p");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        byte[] data = process.getInputStream().readAllBytes();
        process.waitFor();

        return data;
    }

    /**
     * Taps at screen coordinates.
     */
    public static boolean tap(String deviceSerial, int x, int y) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        return process.waitFor() == 0;
    }

    /**
     * Swipes from one point to another.
     */
    public static boolean swipe(String deviceSerial, int x1, int y1, int x2, int y2, int durationMs) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "input", "swipe",
            String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        return process.waitFor() == 0;
    }

    /**
     * Inputs text.
     */
    public static boolean inputText(String deviceSerial, String text) throws IOException, InterruptedException {
        // Escape special characters for shell
        String escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'");
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "input", "text", escaped);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        return process.waitFor() == 0;
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

        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "input", "keyevent", code);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        return process.waitFor() == 0;
    }

    /**
     * Sets up port forwarding to a Unix domain socket.
     */
    public static boolean setupPortForward(String deviceSerial, int localPort, String socketName) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(deviceSerial, "forward", "tcp:" + localPort, "localabstract:" + socketName);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        return process.waitFor() == 0;
    }

    /**
     * Removes port forwarding.
     */
    public static void removePortForward(String deviceSerial, int localPort) {
        try {
            List<String> cmd = buildAdbCommand(deviceSerial, "forward", "--remove", "tcp:" + localPort);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.start().waitFor();
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

    private static String extractProp(String props, String name) {
        Pattern p = Pattern.compile(name + ":(\\S+)");
        Matcher m = p.matcher(props);
        return m.find() ? m.group(1) : null;
    }

    private static String readStream(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
}
