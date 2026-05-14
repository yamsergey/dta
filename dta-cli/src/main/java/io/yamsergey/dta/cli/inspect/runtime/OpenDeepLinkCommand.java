package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * {@code dta-cli inspect runtime open-deeplink <package> --uri <uri>} — fires
 * {@code Intent.ACTION_VIEW} from the foreground activity. Works across nav
 * libraries — what the destination needs is an {@code <intent-filter><data>}
 * declaration in its manifest.
 */
@Command(name = "open-deeplink",
         mixinStandardHelpOptions = true,
         description = "Open a deep-link URI in the host app via Intent.ACTION_VIEW.")
public class OpenDeepLinkCommand extends AbstractRuntimeCommand {

    @Option(names = {"--uri"}, required = true,
            description = "Full URI to launch (e.g. 'myapp://topic/42').")
    private String uri;

    @Override
    protected String progressMessage() {
        return "Opening deep link " + uri + " on " + packageName + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("uri", uri));
        return daemon.openDeepLink(packageName, deviceSerial, json);
    }
}
