package io.yamsergey.adt.cli.sidekick;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI command for attaching the sidekick JVMTI agent to an Android app.
 *
 * <p>This command restarts the target app with the JVMTI agent attached at startup,
 * enabling full class retransformation capabilities required for network hooking.</p>
 *
 * <p>The key difference from Debug.attachJvmtiAgent() is that this uses
 * {@code am start-activity --attach-agent} which attaches the agent during app
 * initialization, before the VM evaluates capabilities.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Attach agent and restart app
 * adt-cli sidekick attach com.example.myapp
 *
 * # Attach to specific device
 * adt-cli sidekick attach -d emulator-5554 com.example.myapp
 *
 * # Specify custom agent path
 * adt-cli sidekick attach --agent-path /data/local/tmp/libsidekick-agent.so com.example.myapp
 * </pre>
 */
@Command(name = "attach",
         description = "Attach sidekick agent to an app (restarts the app with agent at startup).")
public class AttachCommand implements Callable<Integer> {

    private static final String AGENT_LIB_NAME = "libsidekick-agent.so";

    @Parameters(index = "0", description = "Package name of the target app (e.g., com.example.myapp)")
    private String packageName;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--adb-path"},
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath = "adb";

    @Option(names = {"--agent-path"},
            description = "Path to agent library on device. If not specified, auto-detected.")
    private String agentPath;

    @Option(names = {"--timeout"},
            description = "Timeout in seconds for ADB commands (default: 30).")
    private int timeoutSeconds = 30;

    @Option(names = {"--no-restart"},
            description = "Don't restart if already running. Just attach to running process (limited capabilities).")
    private boolean noRestart;

    @Override
    public Integer call() throws Exception {
        System.err.println("Attaching sidekick agent to: " + packageName);
        if (deviceSerial != null) {
            System.err.println("Device: " + deviceSerial);
        }

        // Step 1: Check if app is debuggable
        System.err.println("\n[1/5] Checking if app is debuggable...");
        if (!isAppDebuggable()) {
            System.err.println("Error: App is not debuggable. JVMTI agents can only attach to debuggable apps.");
            System.err.println("Ensure android:debuggable=\"true\" is set in AndroidManifest.xml");
            return 1;
        }
        System.err.println("  ✓ App is debuggable");

        // Step 2: Find agent library path
        System.err.println("\n[2/5] Locating agent library...");
        String agentLibPath = findAgentPath();
        if (agentLibPath == null) {
            System.err.println("Error: Agent library not found on device.");
            System.err.println("Make sure the app includes adt-sidekick dependency and was run at least once.");
            return 1;
        }
        System.err.println("  ✓ Agent found: " + agentLibPath);

        // Step 3: Find launcher activity
        System.err.println("\n[3/5] Finding launcher activity...");
        String launcherActivity = findLauncherActivity();
        if (launcherActivity == null) {
            System.err.println("Error: Could not find launcher activity for package: " + packageName);
            return 1;
        }
        System.err.println("  ✓ Launcher: " + launcherActivity);

        // Step 4: Force stop the app
        System.err.println("\n[4/5] Stopping app...");
        if (!forceStopApp()) {
            System.err.println("Warning: Could not force stop app (may not be running)");
        } else {
            System.err.println("  ✓ App stopped");
        }

        // Brief pause to ensure process is fully stopped
        Thread.sleep(500);

        // Step 5: Start app with agent attached
        System.err.println("\n[5/5] Starting app with agent attached...");
        if (!startAppWithAgent(launcherActivity, agentLibPath)) {
            System.err.println("Error: Failed to start app with agent");
            return 1;
        }
        System.err.println("  ✓ App started with JVMTI agent attached");

        System.err.println("\n" + "=".repeat(60));
        System.err.println("SUCCESS: Sidekick agent attached at startup");
        System.err.println("The app now has full class retransformation capabilities.");
        System.err.println("Network hooks will be active for this session.");
        System.err.println("=".repeat(60));

        return 0;
    }

    private boolean isAppDebuggable() throws Exception {
        String output = runAdbCommand("shell", "run-as", packageName, "id");
        // If run-as succeeds, app is debuggable
        return output != null && !output.contains("not debuggable") && !output.contains("Unknown package");
    }

    private String findAgentPath() throws Exception {
        // If user specified a path, use it
        if (agentPath != null && !agentPath.isEmpty()) {
            // Verify it exists
            String check = runAdbCommand("shell", "ls", agentPath);
            if (check != null && !check.contains("No such file")) {
                return agentPath;
            }
            System.err.println("Warning: Specified agent path not found, trying auto-detection...");
        }

        // Try 1: Native lib directory (extractNativeLibs=true)
        String nativeLibPath = String.format("/data/app/~~*/~%s*/lib/arm64/%s", packageName, AGENT_LIB_NAME);
        String result = runAdbCommand("shell", "ls", nativeLibPath);
        if (result != null && !result.contains("No such file") && result.contains(AGENT_LIB_NAME)) {
            // Parse actual path from ls output
            String actualPath = result.trim().split("\n")[0];
            return actualPath;
        }

        // Try 2: Find in data/app with proper expansion
        String findResult = runAdbCommand("shell", "find", "/data/app", "-name", AGENT_LIB_NAME, "-path", "*" + packageName + "*", "2>/dev/null");
        if (findResult != null && !findResult.isEmpty() && findResult.contains(AGENT_LIB_NAME)) {
            String actualPath = findResult.trim().split("\n")[0];
            return actualPath;
        }

        // Try 3: Code cache directory (using run-as for debuggable apps)
        String codeCachePath = "/data/data/" + packageName + "/code_cache/jvmti/" + AGENT_LIB_NAME;
        result = runAdbCommand("shell", "run-as", packageName, "ls", codeCachePath);
        if (result != null && !result.contains("No such file") && result.contains(AGENT_LIB_NAME)) {
            return codeCachePath;
        }

        // Try 4: APK lib directory directly
        String apkLibPath = getApkNativeLibPath();
        if (apkLibPath != null) {
            return apkLibPath;
        }

        return null;
    }

    private String getApkNativeLibPath() throws Exception {
        // Get APK path
        String pmOutput = runAdbCommand("shell", "pm", "path", packageName);
        if (pmOutput == null || !pmOutput.contains("package:")) {
            return null;
        }

        String apkPath = pmOutput.replace("package:", "").trim().split("\n")[0];

        // Get native lib directory from package info
        String dumpOutput = runAdbCommand("shell", "dumpsys", "package", packageName);
        if (dumpOutput == null) {
            return null;
        }

        // Look for nativeLibraryDir in dumpsys output
        Pattern pattern = Pattern.compile("nativeLibraryDir=([^\\s]+)");
        Matcher matcher = pattern.matcher(dumpOutput);
        if (matcher.find()) {
            String nativeLibDir = matcher.group(1);
            String agentFullPath = nativeLibDir + "/" + AGENT_LIB_NAME;

            // Verify it exists
            String check = runAdbCommand("shell", "ls", agentFullPath);
            if (check != null && !check.contains("No such file")) {
                return agentFullPath;
            }
        }

        return null;
    }

    private String findLauncherActivity() throws Exception {
        // Use pm dump to find launcher activity
        String output = runAdbCommand("shell", "cmd", "package", "resolve-activity",
                "--brief", "-c", "android.intent.category.LAUNCHER", packageName);

        if (output != null && output.contains("/")) {
            // Format: priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            //         com.example.app/.MainActivity
            String[] lines = output.trim().split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.contains("/")) {
                    return line;
                }
            }
        }

        // Fallback: try dumpsys package
        output = runAdbCommand("shell", "dumpsys", "package", packageName);
        if (output != null) {
            // Look for MAIN/LAUNCHER activity
            Pattern pattern = Pattern.compile(packageName + "/([^\\s]+).*MAIN.*LAUNCHER", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return packageName + "/" + matcher.group(1);
            }

            // Alternative: look for Activity Resolver Table
            pattern = Pattern.compile("android\\.intent\\.action\\.MAIN.*?(" + packageName + "/[^\\s]+)", Pattern.DOTALL);
            matcher = pattern.matcher(output);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return null;
    }

    private boolean forceStopApp() throws Exception {
        String output = runAdbCommand("shell", "am", "force-stop", packageName);
        return output != null;
    }

    private boolean startAppWithAgent(String activity, String agentLibPath) throws Exception {
        // Use am start-activity with --attach-agent
        String output = runAdbCommand("shell", "am", "start-activity",
                "--attach-agent", agentLibPath,
                "-n", activity,
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER");

        return output != null && !output.contains("Error");
    }

    private String runAdbCommand(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(adbPath);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            command.add("-s");
            command.add(deviceSerial);
        }

        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return null;
        }

        return output.toString();
    }
}
