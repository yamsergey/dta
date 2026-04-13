package io.yamsergey.dta.daemon.runner;

import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Builds, installs, and launches an Android app with dta-sidekick auto-injected.
 *
 * <p>Uses a Gradle init script to inject the sidekick dependency without modifying
 * project files, then installs the APK and launches the main activity.</p>
 */
public class AppRunner {

    private static final Logger log = LoggerFactory.getLogger(AppRunner.class);
    private static final String SIDEKICK_VERSION = readSidekickVersion();

    public record RunRequest(
        String projectPath,
        String device,
        String variant,
        String module,
        String activity
    ) {
        public RunRequest {
            if (projectPath == null || projectPath.isEmpty()) throw new IllegalArgumentException("projectPath required");
            if (variant == null || variant.isEmpty()) variant = "debug";
            if (module == null || module.isEmpty()) module = ":app";
            if (activity != null && activity.isEmpty()) activity = null;
        }
    }

    public record RunResult(
        boolean success,
        String packageName,
        String apkPath,
        String buildLog,
        String launchActivity,
        String error
    ) {
        public static RunResult failure(String error, String buildLog) {
            return new RunResult(false, null, null, buildLog, null, error);
        }
    }

    public interface ProgressListener {
        void onProgress(String stage, String message);
    }

    private final SidekickConnectionManager connectionManager = SidekickConnectionManager.getInstance();

    public RunResult run(RunRequest request, ProgressListener listener) {
        StringBuilder buildLog = new StringBuilder();

        try {
            // 1. Generate init script
            progress(listener, "INIT_SCRIPT", "Generating sidekick init script...");
            File initScript = generateInitScript(request.variant());
            log.info("Init script: {}", initScript.getAbsolutePath());

            // 2. Build
            progress(listener, "BUILD", "Building " + request.module() + " [" + request.variant() + "]...");
            int exitCode = runGradleBuild(request.projectPath(), request.module(),
                request.variant(), initScript, buildLog, listener);
            if (exitCode != 0) {
                return RunResult.failure("Gradle build failed (exit code " + exitCode + ")", buildLog.toString());
            }

            // 3. Find APK
            progress(listener, "APK_DISCOVERY", "Locating APK...");
            String apkPath = findApk(request.projectPath(), request.module(), request.variant());
            if (apkPath == null) {
                return RunResult.failure("Could not find APK for variant " + request.variant(), buildLog.toString());
            }
            log.info("APK found: {}", apkPath);

            // 4. Get package name and launcher activity from APK
            ApkInfo apkInfo = discoverApkInfo(apkPath);
            String packageName = apkInfo.packageName();
            log.info("Package: {}", packageName);

            // 5. Install
            progress(listener, "INSTALL", "Installing " + packageName + "...");
            connectionManager.installApk(request.device(), apkPath);

            // Brief pause after install — the system needs time to kill the old process
            // and register the new APK before we can launch
            Thread.sleep(1000);

            // 6. Launch — priority: user override → aapt2 launchable-activity → device resolve-activity.
            // aapt2 is preferred over resolve-activity because debug builds sometimes have multiple
            // launcher activities and resolve-activity picks the wrong one.
            progress(listener, "LAUNCH", "Launching " + packageName + "...");
            String component;
            if (request.activity() != null) {
                component = packageName + "/" + normalizeActivity(request.activity());
            } else if (apkInfo.launcherActivity() != null) {
                component = packageName + "/" + apkInfo.launcherActivity();
            } else {
                component = connectionManager.resolveMainActivity(request.device(), packageName);
            }
            connectionManager.launchActivity(request.device(), component);
            log.info("Launched: {}", component);

            return new RunResult(true, packageName, apkPath, buildLog.toString(), component, null);

        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);
            return RunResult.failure(e.getMessage(), buildLog.toString());
        }
    }

    // ========================================================================
    // Init script generation
    // ========================================================================

    File generateInitScript(String variant) throws IOException {
        // Always use debugImplementation — sidekick requires a debuggable build
        // (uses JVMTI hooks). This covers all debug variants: debug, stagingDebug, etc.
        //
        // When the version is a SNAPSHOT, also add the Sonatype snapshots repo
        // so Gradle can resolve it. Release versions resolve from mavenCentral().
        boolean isSnapshot = SIDEKICK_VERSION.contains("SNAPSHOT");
        String snapshotRepo = isSnapshot
            ? "\n                        maven { url 'https://central.sonatype.com/repository/maven-snapshots/' }"
            : "";
        String script = """
            // Auto-generated by DTA
            beforeSettings {
                it.dependencyResolutionManagement {
                    repositories {
                        mavenCentral()%s
                    }
                }
            }
            allprojects {
                afterEvaluate {
                    if (plugins.hasPlugin('com.android.application') || plugins.hasPlugin('com.android.library')) {
                        dependencies {
                            debugImplementation 'io.github.yamsergey:dta-sidekick:%s'
                        }
                        logger.lifecycle("[DTA] Injected dta-sidekick:%s into ${project.name} [%s]")
                    }
                }
            }
            """.formatted(snapshotRepo, SIDEKICK_VERSION, SIDEKICK_VERSION, variant);

        File tmpFile = File.createTempFile("dta-sidekick-inject-", ".gradle");
        tmpFile.deleteOnExit();
        Files.writeString(tmpFile.toPath(), script);
        return tmpFile;
    }

    // ========================================================================
    // Gradle build
    // ========================================================================

    private int runGradleBuild(String projectPath, String module, String variant,
                               File initScript, StringBuilder buildLog,
                               ProgressListener listener) throws IOException, InterruptedException {
        Path projectDir = Path.of(projectPath);
        String gradlew = findGradlew(projectDir);

        // assemble{Variant} with first letter capitalized
        String taskName = module + ":assemble" + capitalize(variant);

        List<String> cmd = new ArrayList<>();
        cmd.add(gradlew);
        cmd.add(taskName);
        cmd.add("--init-script");
        cmd.add(initScript.getAbsolutePath());

        log.info("Gradle command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Stream output
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                buildLog.append(line).append('\n');
                progress(listener, "BUILD", line);
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Gradle build timed out after 10 minutes");
        }
        return process.exitValue();
    }

    private String findGradlew(Path projectDir) throws IOException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String name = isWindows ? "gradlew.bat" : "gradlew";
        Path gradlew = projectDir.resolve(name);
        if (!Files.isExecutable(gradlew)) {
            throw new IOException("Gradle wrapper not found or not executable: " + gradlew);
        }
        return gradlew.toAbsolutePath().toString();
    }

    // ========================================================================
    // APK discovery
    // ========================================================================

    String findApk(String projectPath, String module, String variant) throws IOException {
        // Module ":app" -> "app", ":feature:detail" -> "feature/detail"
        String modulePath = module.replaceFirst("^:", "").replace(':', '/');
        Path outputDir = Path.of(projectPath, modulePath, "build", "outputs", "apk");

        if (!Files.isDirectory(outputDir)) {
            return null;
        }

        // Split camelCase variant into segments for path matching.
        // "exampleAppUatDebug" → ["example", "app", "uat", "debug"]
        // APK path uses these as directories: exampleApp/uat/debug/app-exampleApp-uat-debug.apk
        List<String> variantSegments = splitCamelCase(variant);

        // Search for APKs where all variant segments appear in the relative path
        try (Stream<Path> walk = Files.walk(outputDir, 6)) {
            return walk
                .filter(p -> p.toString().endsWith(".apk"))
                .filter(p -> !p.getFileName().toString().contains("androidTest"))
                .filter(p -> {
                    String rel = outputDir.relativize(p).toString().toLowerCase();
                    return variantSegments.stream().allMatch(seg -> rel.contains(seg.toLowerCase()));
                })
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
        }
    }

    /**
     * Splits a camelCase string into lowercase segments.
     * "exampleAppUatDebug" → ["example", "app", "uat", "debug"]
     * "debug" → ["debug"]
     */
    private static List<String> splitCamelCase(String s) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && current.length() > 0) {
                segments.add(current.toString().toLowerCase());
                current = new StringBuilder();
            }
            current.append(c);
        }
        if (current.length() > 0) {
            segments.add(current.toString().toLowerCase());
        }
        return segments;
    }

    // ========================================================================
    // Package name discovery
    // ========================================================================

    /**
     * Information extracted from APK via aapt2.
     */
    record ApkInfo(String packageName, String launcherActivity) {}

    ApkInfo discoverApkInfo(String apkPath) throws IOException, InterruptedException {
        String aapt2 = findAapt2();

        ProcessBuilder pb = new ProcessBuilder(aapt2, "dump", "badging", apkPath);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("aapt2 timed out");
        }

        // Parse package name
        String packageName = null;
        Matcher pkgMatcher = Pattern.compile("package:\\s+name='([^']+)'").matcher(output);
        if (pkgMatcher.find()) {
            packageName = pkgMatcher.group(1);
        }
        if (packageName == null) {
            throw new IOException("Could not parse package name from aapt2 output");
        }

        // Parse launcher activity — aapt2 picks the primary one (MAIN+LAUNCHER+DEFAULT),
        // matching Android Studio's DefaultActivityLocator behavior
        String launcherActivity = null;
        Matcher actMatcher = Pattern.compile("launchable-activity:\\s+name='([^']+)'").matcher(output);
        if (actMatcher.find()) {
            launcherActivity = actMatcher.group(1);
        }

        return new ApkInfo(packageName, launcherActivity);
    }

    private String findAapt2() throws IOException {
        // Check ANDROID_HOME
        for (String envVar : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String sdk = System.getenv(envVar);
            if (sdk != null && !sdk.isEmpty()) {
                String aapt2 = findAapt2InSdk(sdk);
                if (aapt2 != null) return aapt2;
            }
        }
        // Common locations
        String home = System.getProperty("user.home");
        for (String rel : new String[]{
                "Library/Android/sdk",   // macOS
                "Android/Sdk",           // Linux
        }) {
            String aapt2 = findAapt2InSdk(Path.of(home, rel).toString());
            if (aapt2 != null) return aapt2;
        }
        // Fallback to PATH
        return "aapt2";
    }

    private String findAapt2InSdk(String sdkPath) {
        Path buildTools = Path.of(sdkPath, "build-tools");
        if (!Files.isDirectory(buildTools)) return null;
        try (Stream<Path> dirs = Files.list(buildTools)) {
            return dirs
                .filter(Files::isDirectory)
                .sorted(Comparator.reverseOrder()) // latest version first
                .map(d -> d.resolve("aapt2"))
                .filter(Files::isExecutable)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Normalizes an activity name for use in {@code am start -n package/activity}.
     * Accepts fully-qualified ({@code io.example.MainActivity}), relative ({@code .MainActivity}),
     * or bare ({@code MainActivity}). Bare names get a leading dot so {@code am start} resolves
     * them against the package.
     */
    private static String normalizeActivity(String activity) {
        if (activity.contains(".")) return activity;
        return "." + activity;
    }

    private static void progress(ProgressListener listener, String stage, String message) {
        if (listener != null) {
            listener.onProgress(stage, message);
        }
    }

    /**
     * Reads the sidekick version from {@code /version.properties} on the
     * classpath. Generated at build time from {@code dtaVersion} in
     * gradle.properties — so the auto-inject init script always matches
     * the version of the tools the user has installed.
     */
    private static String readSidekickVersion() {
        try (var is = AppRunner.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String version = props.getProperty("version", "0.9.35");
                // The plugin/CLI use unique versions like 0.9.36-SNAPSHOT.9 but
                // the Maven artifact is published as 0.9.36-SNAPSHOT (mutable).
                // Strip the build number so the init script resolves correctly.
                if (version.contains("-SNAPSHOT.")) {
                    version = version.replaceFirst("-SNAPSHOT\\.\\d+$", "-SNAPSHOT");
                }
                return version;
            }
        } catch (Exception ignored) {}
        return "0.9.35";
    }
}
