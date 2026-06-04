package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dta-cli inspect runtime threads <package>} — lists all threads
 * with state. Use {@code --stack-traces} for full traces (off by default
 * because traces can be large).
 */
@Command(name = "threads",
         mixinStandardHelpOptions = true,
         description = "List threads (states; optionally with stack traces).")
public class ThreadsCommand extends AbstractRuntimeCommand {

    @Option(names = {"--stack-traces"},
            description = "Include stack traces for each thread.")
    private boolean stackTraces;

    @Override
    protected String progressMessage() {
        return "Fetching threads for " + packageName
                + (stackTraces ? " (with stack traces)" : "") + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.threads(packageName, deviceSerial, stackTraces);
    }
}
