package io.yamsergey.dta.cli.run;

import io.yamsergey.dta.tools.android.runner.AppRunner;
import io.yamsergey.dta.tools.android.runner.AppRunner.RunRequest;
import io.yamsergey.dta.tools.android.runner.AppRunner.RunResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command that builds and launches an Android app with dta-sidekick auto-injected.
 *
 * <p>Example usage:</p>
 * <pre>
 * dta-cli run --project /path/to/app --variant debug
 * dta-cli run -p /path/to/app -v stagingDebug -d emulator-5554
 * dta-cli run -p /path/to/app -m :feature:app
 * </pre>
 */
@Command(name = "run",
         description = "Build and launch an Android app with dta-sidekick injected.")
public class RunCommand implements Callable<Integer> {

    @Option(names = {"--project", "-p"}, required = true,
            description = "Path to the Android project root")
    private String projectPath;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--variant", "-v"}, defaultValue = "debug",
            description = "Build variant — must be a debug build type (default: debug)")
    private String variant;

    @Option(names = {"--module", "-m"}, defaultValue = ":app",
            description = "Gradle module (default: :app)")
    private String module;

    @Option(names = {"--activity", "-a"},
            description = "Activity to launch (default: auto-detected from APK). " +
                    "Accepts fully-qualified (io.example.MainActivity), relative (.MainActivity), or bare (MainActivity).")
    private String activity;

    @Override
    public Integer call() {
        AppRunner runner = new AppRunner();
        RunRequest request = new RunRequest(projectPath, deviceSerial, variant, module, activity);

        RunResult result = runner.run(request, (stage, message) -> {
            if ("BUILD".equals(stage)) {
                System.out.println(message);
            } else {
                System.err.println("[" + stage + "] " + message);
            }
        });

        if (result.success()) {
            System.err.println();
            System.err.println("App launched: " + result.packageName());
            System.err.println("Activity:    " + result.launchActivity());
            System.err.println("APK:         " + result.apkPath());
            return 0;
        } else {
            System.err.println();
            System.err.println("Error: " + result.error());
            return 1;
        }
    }
}
