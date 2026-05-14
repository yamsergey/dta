package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime app-functions <package>} — enumerates
 * AppFunctions declared via {@code androidx.appfunctions} on the host app.
 * See {@code mcp__dta__app_runtime command=app_functions} for the JSON
 * shape; this command returns the same payload.
 */
@Command(name = "app-functions",
         mixinStandardHelpOptions = true,
         description = "Enumerate androidx.appfunctions methods exposed by the host app.")
public class AppFunctionsCommand extends AbstractRuntimeCommand {

    @Override
    protected String progressMessage() {
        return "Enumerating AppFunctions for " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.appFunctions(packageName, deviceSerial);
    }
}
