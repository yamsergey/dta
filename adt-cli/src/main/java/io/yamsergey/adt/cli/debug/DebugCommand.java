package io.yamsergey.adt.cli.debug;

import io.yamsergey.adt.debug.DebugServerApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI command for starting a complete debug session.
 *
 * <p>This command provides a unified workflow for debugging Android apps:</p>
 * <ol>
 *   <li>Build the app (optional)</li>
 *   <li>Install the APK</li>
 *   <li>Attach the JVMTI sidekick agent</li>
 *   <li>Start the debug server with REST API</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Full workflow: build, install, attach, serve
 * adt-cli debug ~/projects/my-app --variant debug
 *
 * # Skip build, use existing APK
 * adt-cli debug ~/projects/my-app --apk app/build/outputs/apk/debug/app-debug.apk
 *
 * # Specify server port
 * adt-cli debug ~/projects/my-app --port 9000
 * </pre>
 */
@Command(name = "debug",
         description = "Start a debug session for an Android app.",
         mixinStandardHelpOptions = true)
public class DebugCommand implements Callable<Integer> {

    private static final String AGENT_LIB_NAME = "libsidekick-agent.so";

    @Parameters(index = "0", description = "Path to Android project")
    private Path projectPath;

    @Option(names = {"--variant"}, defaultValue = "debug",
            description = "Build variant (default: debug)")
    private String variant;

    @Option(names = {"--apk"},
            description = "Path to pre-built APK (skips build step)")
    private Path apkPath;

    @Option(names = {"--port"}, defaultValue = "8700",
            description = "Debug server port (default: 8700)")
    private int serverPort;

    @Option(names = {"-d", "--device"},
            description = "Target device serial number")
    private String deviceSerial;

    @Option(names = {"--adb-path"}, defaultValue = "adb",
            description = "Path to ADB executable")
    private String adbPath;

    @Option(names = {"--timeout"}, defaultValue = "60",
            description = "Build timeout in seconds (default: 60)")
    private int timeoutSeconds;

    @Option(names = {"--no-build"},
            description = "Skip building even if no --apk specified")
    private boolean noBuild;

    @Option(names = {"--package", "-p"},
            description = "Package name (auto-detected if not specified)")
    private String packageName;

    @Override
    public Integer call() throws Exception {
        System.err.println("Starting debug session...");
        System.err.println("Project: " + projectPath.toAbsolutePath());

        // Step 1: Build (if needed)
        if (apkPath == null && !noBuild) {
            System.err.println("\n[1/5] Building project...");
            if (!buildProject()) {
                System.err.println("Error: Build failed");
                return 1;
            }
            System.err.println("  ✓ Build successful");
        } else if (apkPath != null) {
            System.err.println("\n[1/5] Using pre-built APK: " + apkPath);
        } else {
            System.err.println("\n[1/5] Skipping build (--no-build)");
        }

        // Find APK if not specified
        if (apkPath == null) {
            apkPath = findApk();
            if (apkPath == null) {
                System.err.println("Error: Could not find APK. Build the project or specify --apk");
                return 1;
            }
        }

        // Get package name (from option or auto-detect)
        if (packageName == null || packageName.isEmpty()) {
            packageName = getPackageNameFromApk(apkPath.toFile());
            if (packageName == null) {
                System.err.println("Error: Could not determine package name. Use --package option.");
                return 1;
            }
        }
        System.err.println("  Package: " + packageName);

        // Step 2: Install APK
        System.err.println("\n[2/5] Installing APK...");
        if (!installApk()) {
            System.err.println("Error: Installation failed");
            return 1;
        }
        System.err.println("  ✓ APK installed");

        // Step 3: Attach sidekick agent
        System.err.println("\n[3/5] Attaching sidekick agent...");
        if (!attachSidekick()) {
            System.err.println("Error: Failed to attach agent");
            return 1;
        }
        System.err.println("  ✓ Agent attached");

        // Step 4: Generate access token
        String accessToken = DebugServerApplication.generateToken();

        // Step 5: Start debug server
        System.err.println("\n[4/5] Starting debug server...");
        try {
            DebugServerApplication.start(serverPort, accessToken, deviceSerial, packageName);
            System.err.println("  ✓ Server started on port " + serverPort);
        } catch (Exception e) {
            System.err.println("Error: Failed to start debug server: " + e.getMessage());
            return 1;
        }

        // Print access info
        System.err.println("\n[5/5] Ready!");
        printAccessInfo(accessToken);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("\nShutting down debug session...");
            DebugServerApplication.stop();
        }));

        // Wait for Ctrl+C
        System.err.println("\nPress Ctrl+C to stop the debug session.");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 0;
    }

    private void printAccessInfo(String accessToken) {
        String line = "═".repeat(60);
        System.err.println("\n" + line);
        System.err.println("  DEBUG SESSION ACTIVE");
        System.err.println(line);
        System.err.println();
        System.err.println("  Server:  http://localhost:" + serverPort);
        System.err.println("  Token:   " + accessToken);
        System.err.println();
        System.err.println("  Usage:");
        System.err.println("    curl -H \"Authorization: Bearer " + accessToken + "\" \\");
        System.err.println("         http://localhost:" + serverPort + "/events");
        System.err.println();
        System.err.println("  Endpoints:");
        System.err.println("    GET  /events       - All events");
        System.err.println("    GET  /network      - HTTP requests");
        System.err.println("    GET  /health       - Server health (no auth)");
        System.err.println();
        System.err.println(line);
    }

    private boolean buildProject() throws Exception {
        Path gradlew = projectPath.resolve("gradlew");
        if (!Files.exists(gradlew)) {
            System.err.println("Error: gradlew not found in " + projectPath);
            return false;
        }

        // Determine task name
        String taskName = "assemble" + capitalize(variant);

        ProcessBuilder pb = new ProcessBuilder(gradlew.toString(), taskName, "--console=plain");
        pb.directory(projectPath.toFile());
        pb.redirectErrorStream(true);
        pb.inheritIO();

        Process process = pb.start();
        boolean completed = process.waitFor(timeoutSeconds * 5L, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            return false;
        }

        return process.exitValue() == 0;
    }

    private Path findApk() {
        // Look in standard locations
        String[] patterns = {
                "app/build/outputs/apk/" + variant + "/app-" + variant + ".apk",
                "app/build/outputs/apk/" + variant + "/*.apk",
                "**/build/outputs/apk/" + variant + "/*.apk"
        };

        for (String pattern : patterns) {
            Path apkPath = projectPath.resolve(pattern.replace("*", ""));
            if (Files.exists(apkPath)) {
                return apkPath;
            }
        }

        // Try to find any APK
        try {
            return Files.walk(projectPath)
                    .filter(p -> p.toString().endsWith(".apk"))
                    .filter(p -> p.toString().contains(variant))
                    .filter(p -> !p.toString().contains("androidTest"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String getPackageNameFromApk(File apkFile) throws Exception {
        // Try aapt2 first (Android SDK)
        try {
            ProcessBuilder pb = new ProcessBuilder("aapt2", "dump", "badging", apkFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("package:")) {
                        Pattern pattern = Pattern.compile("name='([^']+)'");
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            return matcher.group(1);
                        }
                    }
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // aapt2 not available, try alternative methods
        }

        // Try using Android SDK's aapt (older)
        try {
            ProcessBuilder pb = new ProcessBuilder("aapt", "dump", "badging", apkFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("package:")) {
                        Pattern pattern = Pattern.compile("name='([^']+)'");
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            return matcher.group(1);
                        }
                    }
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // aapt not available either
        }

        // Fallback: Install APK and get package from pm list
        String installOutput = runAdbCommand("install", "-r", "-t", apkFile.getAbsolutePath());
        if (installOutput != null && installOutput.contains("Success")) {
            // Get most recently installed package
            String pmOutput = runAdbCommand("shell", "pm", "list", "packages", "-3", "--show-versioncode");
            if (pmOutput != null) {
                // Return first third-party package (likely the one we just installed)
                String[] lines = pmOutput.split("\n");
                for (String line : lines) {
                    if (line.startsWith("package:")) {
                        String pkg = line.replace("package:", "").split(" ")[0].trim();
                        // Verify this package's APK path matches
                        String pathOutput = runAdbCommand("shell", "pm", "path", pkg);
                        if (pathOutput != null) {
                            return pkg;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean installApk() throws Exception {
        String output = runAdbCommand("install", "-r", "-t", apkPath.toAbsolutePath().toString());
        return output != null && output.contains("Success");
    }

    private boolean attachSidekick() throws Exception {
        // Extract agent library from APK (no app launch needed)
        String agentPath = extractOrFindAgentLibrary();
        if (agentPath == null) {
            System.err.println("Error: Agent library not found on device");
            return false;
        }

        // Find launcher activity
        String launcherActivity = findLauncherActivity();
        if (launcherActivity == null) {
            System.err.println("Error: Could not find launcher activity");
            return false;
        }

        // Force stop any existing instance
        runAdbCommand("shell", "am", "force-stop", packageName);
        Thread.sleep(500);

        // Start with agent
        String output = runAdbCommand("shell", "am", "start-activity",
                "--attach-agent", agentPath,
                "-n", launcherActivity,
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER");

        return output != null && !output.contains("Error");
    }

    /**
     * Extracts the agent library from APK via ADB, or finds it if already extracted.
     * This avoids the need to launch the app just for library extraction.
     */
    private String extractOrFindAgentLibrary() throws Exception {
        String codeCachePath = "/data/data/" + packageName + "/code_cache/jvmti/" + AGENT_LIB_NAME;

        // Check if already extracted
        String result = runAdbCommand("shell", "run-as", packageName, "ls", codeCachePath);
        if (result != null && !result.contains("No such file") && result.contains(AGENT_LIB_NAME)) {
            return codeCachePath;
        }

        // Check native lib directory (if extractNativeLibs=true in manifest)
        String dumpOutput = runAdbCommand("shell", "dumpsys", "package", packageName);
        if (dumpOutput != null) {
            Pattern nativeLibPattern = Pattern.compile("legacyNativeLibraryDir=([^\\s]+)");
            Matcher matcher = nativeLibPattern.matcher(dumpOutput);
            if (matcher.find()) {
                String nativeLibDir = matcher.group(1);
                // Get primary ABI
                Pattern abiPattern = Pattern.compile("primaryCpuAbi=([^\\s]+)");
                Matcher abiMatcher = abiPattern.matcher(dumpOutput);
                String abi = abiMatcher.find() ? abiMatcher.group(1) : "arm64-v8a";

                String agentFullPath = nativeLibDir + "/" + abi + "/" + AGENT_LIB_NAME;
                String check = runAdbCommand("shell", "ls", agentFullPath);
                if (check != null && !check.contains("No such file") && check.contains(AGENT_LIB_NAME)) {
                    return agentFullPath;
                }
            }
        }

        // Extract from APK using adb
        return extractAgentFromApkViaAdb();
    }

    /**
     * Extracts the native library from APK to code_cache/jvmti using ADB commands.
     */
    private String extractAgentFromApkViaAdb() throws Exception {
        // Get APK path on device
        String pmOutput = runAdbCommand("shell", "pm", "path", packageName);
        if (pmOutput == null || !pmOutput.contains("package:")) {
            System.err.println("  Could not find APK path for " + packageName);
            return null;
        }

        String apkPath = pmOutput.trim().replace("package:", "").split("\n")[0].trim();

        // Get device ABI
        String abiOutput = runAdbCommand("shell", "getprop", "ro.product.cpu.abi");
        String abi = abiOutput != null ? abiOutput.trim() : "arm64-v8a";

        // The library path inside APK
        String libPathInApk = "lib/" + abi + "/" + AGENT_LIB_NAME;

        // Create target directory
        String targetDir = "/data/data/" + packageName + "/code_cache/jvmti";
        runAdbCommand("shell", "run-as", packageName, "mkdir", "-p", targetDir);

        // Extract using unzip to stdout and redirect to file via run-as
        // This works because unzip -p outputs to stdout, which we pipe through run-as
        String targetPath = targetDir + "/" + AGENT_LIB_NAME;

        // Use a temporary file approach since piping through run-as is complex
        String tmpPath = "/data/local/tmp/" + AGENT_LIB_NAME;

        // Extract to tmp - pass entire command as single string to adb shell
        runAdbCommand("shell",
                "unzip -p '" + apkPath + "' '" + libPathInApk + "' > " + tmpPath);

        // Check if extraction worked (file size > 0)
        String sizeOutput = runAdbCommand("shell", "stat -c %s " + tmpPath);
        if (sizeOutput == null || sizeOutput.trim().equals("0") || sizeOutput.contains("No such file")) {
            // Try alternate ABI paths
            String[] abis = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};
            for (String altAbi : abis) {
                if (altAbi.equals(abi)) continue;
                libPathInApk = "lib/" + altAbi + "/" + AGENT_LIB_NAME;
                runAdbCommand("shell",
                        "unzip -p '" + apkPath + "' '" + libPathInApk + "' > " + tmpPath);
                sizeOutput = runAdbCommand("shell", "stat -c %s " + tmpPath);
                if (sizeOutput != null && !sizeOutput.trim().equals("0") && !sizeOutput.contains("No such file")) {
                    break;
                }
            }
        }

        // Verify extraction
        sizeOutput = runAdbCommand("shell", "stat -c %s " + tmpPath);
        if (sizeOutput == null || sizeOutput.trim().equals("0") || sizeOutput.contains("No such file")) {
            System.err.println("  Failed to extract " + AGENT_LIB_NAME + " from APK");
            return null;
        }

        // Copy to app's code_cache using cat through run-as (preserves file content)
        runAdbCommand("shell",
                "run-as " + packageName + " sh -c 'cat " + tmpPath + " > " + targetPath + "'");

        // Make executable
        runAdbCommand("shell", "run-as " + packageName + " chmod 755 " + targetPath);

        // Clean up tmp
        runAdbCommand("shell", "rm -f " + tmpPath);

        // Verify
        String verifyOutput = runAdbCommand("shell", "run-as " + packageName + " ls -l " + targetPath);
        if (verifyOutput != null && verifyOutput.contains(AGENT_LIB_NAME)) {
            System.err.println("  Extracted agent library to " + targetPath);
            return targetPath;
        }

        return null;
    }

    private String findLauncherActivity() throws Exception {
        String output = runAdbCommand("shell", "cmd", "package", "resolve-activity",
                "--brief", "-c", "android.intent.category.LAUNCHER", packageName);

        if (output != null && output.contains("/")) {
            String[] lines = output.trim().split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.contains("/")) {
                    return line;
                }
            }
        }

        return null;
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

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
