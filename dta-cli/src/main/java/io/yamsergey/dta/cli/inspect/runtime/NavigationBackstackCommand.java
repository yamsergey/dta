package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime navigation-backstack <package>} — dumps
 * the current navigation backstack with routes and arguments.
 */
@Command(name = "navigation-backstack",
         mixinStandardHelpOptions = true,
         description = "Show the current navigation backstack (routes + arguments).")
public class NavigationBackstackCommand extends AbstractRuntimeCommand {

    @Override
    protected String progressMessage() {
        return "Fetching navigation backstack for " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.navigationBackstack(packageName, deviceSerial);
    }
}
