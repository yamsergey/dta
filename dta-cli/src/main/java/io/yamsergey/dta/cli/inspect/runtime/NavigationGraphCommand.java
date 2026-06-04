package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime navigation-graph <package>} — dumps the
 * full navigation graph (destinations, routes, deeplinks).
 */
@Command(name = "navigation-graph",
         mixinStandardHelpOptions = true,
         description = "Dump the navigation graph (destinations, routes, deeplinks).")
public class NavigationGraphCommand extends AbstractRuntimeCommand {

    @Override
    protected String progressMessage() {
        return "Fetching navigation graph for " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.navigationGraph(packageName, deviceSerial);
    }
}
