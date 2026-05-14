package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime memory <package>} — heap and native
 * memory usage (heapUsed, heapMax, nativeHeap).
 */
@Command(name = "memory",
         mixinStandardHelpOptions = true,
         description = "Show heap and native memory usage.")
public class MemoryCommand extends AbstractRuntimeCommand {

    @Override
    protected String progressMessage() {
        return "Fetching memory usage for " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.memory(packageName, deviceSerial);
    }
}
