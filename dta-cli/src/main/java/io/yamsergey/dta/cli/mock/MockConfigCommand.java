package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "config",
         description = "Get or update global mock configuration.")
public class MockConfigCommand implements Callable<Integer> {

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--enable"}, description = "Enable global mocking")
    private boolean enable;

    @Option(names = {"--disable"}, description = "Disable global mocking")
    private boolean disable;

    @Option(names = {"--http"}, description = "Enable/disable HTTP mocking")
    private Boolean httpMocking;

    @Option(names = {"--websocket"}, description = "Enable/disable WebSocket mocking")
    private Boolean webSocketMocking;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();

            if (enable || disable || httpMocking != null || webSocketMocking != null) {
                StringBuilder json = new StringBuilder("{");
                boolean first = true;
                if (enable || disable) {
                    json.append("\"enabled\":").append(enable);
                    first = false;
                }
                if (httpMocking != null) {
                    if (!first) json.append(",");
                    json.append("\"httpMockingEnabled\":").append(httpMocking);
                    first = false;
                }
                if (webSocketMocking != null) {
                    if (!first) json.append(",");
                    json.append("\"webSocketMockingEnabled\":").append(webSocketMocking);
                }
                json.append("}");
                System.out.println(daemon.updateMockConfig(packageName, deviceSerial, json.toString()));
            } else {
                System.out.println(daemon.mockConfig(packageName, deviceSerial));
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
