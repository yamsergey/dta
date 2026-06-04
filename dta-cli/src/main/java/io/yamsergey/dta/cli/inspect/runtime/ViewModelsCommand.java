package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime viewmodels <package>} — lists live
 * ViewModels (Activity- and Navigation 3 NavEntry-scoped) with reflected
 * LiveData/StateFlow/Compose state. See
 * {@code mcp__dta__app_runtime command=viewmodels}.
 */
@Command(name = "viewmodels",
         mixinStandardHelpOptions = true,
         description = "List live ViewModels with reflected state values.")
public class ViewModelsCommand extends AbstractRuntimeCommand {

    @Override
    protected String progressMessage() {
        return "Enumerating ViewModels for " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.viewModels(packageName, deviceSerial);
    }
}
