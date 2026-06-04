package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * Shared boilerplate for {@code dta-cli inspect runtime <op>} commands.
 *
 * <p>Every runtime subcommand takes a target package, an optional device
 * serial, and an optional output file. Each concrete subclass implements
 * {@link #fetch} to issue the actual REST call against the daemon.</p>
 */
public abstract class AbstractRuntimeCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Package name of the target application (e.g., com.example.app).")
    protected String packageName;

    @Option(names = {"-d", "--device"},
            description = "Device serial. If omitted, the daemon uses the first available device.")
    protected String deviceSerial;

    @Option(names = {"-o", "--output"},
            description = "Output file path. If omitted, prints to stdout.")
    protected String outputPath;

    @Override
    public Integer call() {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            System.err.println(progressMessage());
            String result = fetch(daemon);
            outputResult(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** Short message printed to stderr while the request is in flight. */
    protected abstract String progressMessage();

    /** Performs the actual daemon call and returns the JSON response. */
    protected abstract String fetch(DaemonClient daemon) throws Exception;

    private void outputResult(String json) throws Exception {
        if (outputPath != null && !outputPath.isEmpty()) {
            File outputFile = new File(outputPath);
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
                System.err.println("Error: Failed to create output directory: " + outputDir);
                return;
            }
            Files.writeString(outputFile.toPath(), json);
            System.err.println("Success: Output saved to " + outputFile.getAbsolutePath());
        } else {
            System.out.println(json);
        }
    }
}
