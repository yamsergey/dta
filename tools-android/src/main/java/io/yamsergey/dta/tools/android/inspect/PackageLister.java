package io.yamsergey.dta.tools.android.inspect;

import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import lombok.Builder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists installed packages on an Android device with debuggability information.
 *
 * <p>Uses ADB to query package information and determine which apps are debuggable.
 * Debuggable apps can be attached to with JVMTI agents for inspection.</p>
 */
@Builder
public class PackageLister {

    private static final Pattern VERSION_NAME_PATTERN = Pattern.compile("versionName=([^\\s]+)");
    private static final Pattern VERSION_CODE_PATTERN = Pattern.compile("versionCode=(\\d+)");
    private static final Pattern FLAGS_PATTERN = Pattern.compile("flags=\\[([^\\]]+)\\]");

    @Builder.Default
    private String adbPath = "adb";

    private String deviceSerial;

    @Builder.Default
    private int timeoutSeconds = 30;

    @Builder.Default
    private boolean onlyDebuggable = false;

    @Builder.Default
    private boolean onlyThirdParty = true;

    /**
     * Lists packages on the connected device.
     *
     * @return Result containing list of PackageInfo objects
     */
    public Result<List<PackageInfo>> list() {
        try {
            // Get list of packages
            List<String> packages = getPackageList();
            if (packages.isEmpty()) {
                return new Failure<>(null, "No packages found on device");
            }

            List<PackageInfo> results = new ArrayList<>();

            for (String packageName : packages) {
                PackageInfo info = getPackageInfo(packageName);
                if (info != null) {
                    if (!onlyDebuggable || info.isDebuggable()) {
                        results.add(info);
                    }
                }
            }

            String description = String.format("Found %d %spackages",
                    results.size(),
                    onlyDebuggable ? "debuggable " : "");

            return new Success<>(results, description);

        } catch (Exception e) {
            return new Failure<>(e, "Failed to list packages: " + e.getMessage());
        }
    }

    private List<String> getPackageList() throws Exception {
        List<String> command = buildAdbCommand("shell", "pm", "list", "packages",
                onlyThirdParty ? "-3" : "");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> packages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("package:")) {
                    packages.add(line.substring(8).trim());
                }
            }
        }

        process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        return packages;
    }

    private PackageInfo getPackageInfo(String packageName) {
        try {
            List<String> command = buildAdbCommand("shell", "dumpsys", "package", packageName);

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

            process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            String dump = output.toString();
            return parsePackageInfo(packageName, dump);

        } catch (Exception e) {
            return null;
        }
    }

    private PackageInfo parsePackageInfo(String packageName, String dump) {
        boolean debuggable = false;
        String versionName = null;
        Integer versionCode = null;
        String installLocation = null;

        // Check for DEBUGGABLE flag
        Matcher flagsMatcher = FLAGS_PATTERN.matcher(dump);
        if (flagsMatcher.find()) {
            String flags = flagsMatcher.group(1);
            debuggable = flags.contains("DEBUGGABLE");
        }

        // Extract version name
        Matcher versionNameMatcher = VERSION_NAME_PATTERN.matcher(dump);
        if (versionNameMatcher.find()) {
            versionName = versionNameMatcher.group(1);
        }

        // Extract version code
        Matcher versionCodeMatcher = VERSION_CODE_PATTERN.matcher(dump);
        if (versionCodeMatcher.find()) {
            try {
                versionCode = Integer.parseInt(versionCodeMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        // Extract install location (codePath)
        if (dump.contains("codePath=")) {
            int start = dump.indexOf("codePath=") + 9;
            int end = dump.indexOf("\n", start);
            if (end > start) {
                installLocation = dump.substring(start, end).trim();
            }
        }

        return PackageInfo.builder()
                .packageName(packageName)
                .debuggable(debuggable)
                .versionName(versionName)
                .versionCode(versionCode)
                .installLocation(installLocation)
                .build();
    }

    private List<String> buildAdbCommand(String... args) {
        List<String> command = new ArrayList<>();
        command.add(adbPath);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            command.add("-s");
            command.add(deviceSerial);
        }

        for (String arg : args) {
            if (arg != null && !arg.isEmpty()) {
                command.add(arg);
            }
        }

        return command;
    }
}
