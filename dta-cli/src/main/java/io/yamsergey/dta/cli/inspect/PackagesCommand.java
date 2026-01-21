package io.yamsergey.dta.cli.inspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.yamsergey.dta.tools.android.inspect.PackageInfo;
import io.yamsergey.dta.tools.android.inspect.PackageLister;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for listing debuggable packages on an Android device.
 *
 * <p>This command lists installed packages that can be debugged using JVMTI agent attachment.
 * By default, it shows only debuggable third-party packages.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # List debuggable packages
 * dta-cli inspect packages
 *
 * # Include system packages
 * dta-cli inspect packages --all
 *
 * # Show all packages (not just debuggable)
 * dta-cli inspect packages --include-non-debuggable
 *
 * # Output as JSON
 * dta-cli inspect packages --json
 *
 * # From specific device
 * dta-cli inspect packages -d emulator-5554
 * </pre>
 */
@Command(name = "packages",
         mixinStandardHelpOptions = true,
         description = "List debuggable packages on Android device.")
public class PackagesCommand implements Callable<Integer> {

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--all", "-a"},
            description = "Include system packages (default: only third-party packages).")
    private boolean includeSystem;

    @Option(names = {"--include-non-debuggable"},
            description = "Show all packages, not just debuggable ones.")
    private boolean includeNonDebuggable;

    @Option(names = {"--json", "-j"},
            description = "Output as JSON.")
    private boolean jsonOutput;

    @Option(names = {"--timeout"},
            description = "Timeout in seconds for ADB commands (default: 30).")
    private Integer timeoutSeconds;

    @Option(names = {"--adb-path"},
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath;

    @Override
    public Integer call() throws Exception {
        PackageLister.PackageListerBuilder builder = PackageLister.builder()
                .onlyDebuggable(!includeNonDebuggable)
                .onlyThirdParty(!includeSystem);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            builder.deviceSerial(deviceSerial);
        }

        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeoutSeconds(timeoutSeconds);
        }

        if (adbPath != null && !adbPath.isEmpty()) {
            builder.adbPath(adbPath);
        }

        if (!jsonOutput) {
            System.err.println("Scanning packages...");
        }

        Result<List<PackageInfo>> result = builder.build().list();

        return switch (result) {
            case Success<List<PackageInfo>> success -> {
                List<PackageInfo> packages = success.value();

                if (jsonOutput) {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    System.out.println(mapper.writeValueAsString(packages));
                } else {
                    if (packages.isEmpty()) {
                        System.out.println("No debuggable packages found.");
                    } else {
                        System.out.println();
                        System.out.println("Debuggable packages:");
                        System.out.println("====================");
                        for (PackageInfo pkg : packages) {
                            printPackage(pkg);
                        }
                        System.out.println();
                        System.out.println("Total: " + packages.size() + " package(s)");
                    }
                }
                yield 0;
            }
            case Failure<List<PackageInfo>> failure -> {
                System.err.println("Error: " + failure.description());
                yield 1;
            }
            default -> {
                System.err.println("Error: Unknown result type");
                yield 1;
            }
        };
    }

    private void printPackage(PackageInfo pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(pkg.getPackageName());

        if (pkg.getVersionName() != null) {
            sb.append(" (").append(pkg.getVersionName());
            if (pkg.getVersionCode() != null) {
                sb.append(" / ").append(pkg.getVersionCode());
            }
            sb.append(")");
        }

        if (!pkg.isDebuggable()) {
            sb.append(" [not debuggable]");
        }

        System.out.println(sb);
    }
}
