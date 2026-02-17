package io.yamsergey.dta.cli.inspector;

import io.yamsergey.dta.server.DtaServerApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "server",
         aliases = {"inspector-web"},
         description = "Start the DTA server (daemon)")
public class ServerCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ServerCommand.class);

    @Option(names = {"--port", "-p"},
            defaultValue = "8080",
            description = "Port to run the server on (default: 8080, use 0 for auto-pick)")
    private int port;

    @Option(names = {"--log-file"},
            description = "Path to log file (enables file logging)")
    private String logFile;

    @Override
    public Integer call() throws Exception {
        if (logFile != null && !logFile.isEmpty()) {
            System.setProperty("dta.log.file", logFile);
        }

        log.info("Starting DTA server on port {}", port == 0 ? "(auto)" : port);
        if (logFile != null) {
            log.info("Logging to file: {}", logFile);
        }
        log.info("Press Ctrl+C to stop");

        DtaServerApplication.start(port, logFile);
        return 0;
    }
}
