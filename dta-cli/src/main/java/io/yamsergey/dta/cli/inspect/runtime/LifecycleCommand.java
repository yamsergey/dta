package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime lifecycle <package>} — lists all
 * activities with their lifecycle state (RESUMED/PAUSED/STOPPED).
 */
@Command(name = "lifecycle",
         mixinStandardHelpOptions = true,
         description = "List activities with their lifecycle state.")
public class LifecycleCommand extends AbstractRuntimeCommand {

    @Override
    protected String progressMessage() {
        return "Fetching activity lifecycle for " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.lifecycle(packageName, deviceSerial);
    }
}
