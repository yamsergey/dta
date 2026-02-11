package io.yamsergey.dta.cli.inspect;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * CLI command to pull the sidekick log file from an Android device.
 *
 * <p>The sidekick writes logs to {@code <cacheDir>/sidekick.log} when file logging
 * is enabled via {@code SidekickConfig.builder().enableFileLogging().build()}.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Pull log to stdout
 * dta-cli inspect log-pull --package com.example.app
 *
 * # Save to a file
 * dta-cli inspect log-pull --package com.example.app -o sidekick.log
 *
 * # From a specific device
 * dta-cli inspect log-pull --package com.example.app -d emulator-5554
 * </pre>
 */
@Command(name = "log-pull",
         description = "Pull sidekick log file from device (requires enableFileLogging() in SidekickConfig).")
public class LogPullCommand implements Callable<Integer> {

    private static final String LOG_FILE_NAME = "sidekick.log";

    @Option(names = {"-p", "--package"},
            description = "App package name.",
            required = true)
    private String packageName;

    @Option(names = {"-d", "--device"},
            description = "Device serial number.")
    private String deviceSerial;

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Override
    public Integer call() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            cmd.add("-s");
            cmd.add(deviceSerial);
        }
        cmd.add("shell");
        cmd.add("run-as");
        cmd.add(packageName);
        cmd.add("cat");
        cmd.add("cache/" + LOG_FILE_NAME);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> dataFuture = executor.submit(
                    () -> process.getInputStream().readAllBytes());

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("Error: Timed out pulling log file");
                return 1;
            }

            byte[] data = dataFuture.get(5, TimeUnit.SECONDS);

            if (process.exitValue() != 0) {
                String error = new String(data, StandardCharsets.UTF_8).trim();
                System.err.println("Error: " + error);
                System.err.println("Make sure enableFileLogging() is set in SidekickConfig and the app is debuggable.");
                return 1;
            }

            if (outputPath != null && !outputPath.isEmpty()) {
                File outputFile = new File(outputPath);
                java.nio.file.Files.write(outputFile.toPath(), data);
                System.err.println("Log saved to: " + outputFile.getAbsolutePath());
            } else {
                System.out.write(data);
            }
        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        } finally {
            executor.shutdownNow();
        }

        return 0;
    }
}
