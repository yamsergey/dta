package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "list",
         description = "List all mock rules.")
public class MockListCommand implements Callable<Integer> {

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            System.out.println(daemon.mockRules(packageName, deviceSerial));
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
