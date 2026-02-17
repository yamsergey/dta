package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "disable",
         description = "Disable a mock rule.")
public class MockDisableCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Mock rule ID to disable")
    private String ruleId;

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            String result = daemon.updateMockRule(packageName, ruleId, deviceSerial, "{\"enabled\":false}");
            System.out.println(result);
            System.err.println("Mock rule disabled");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
