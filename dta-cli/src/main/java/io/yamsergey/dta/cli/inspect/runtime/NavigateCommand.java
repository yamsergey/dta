package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code dta-cli inspect runtime navigate <package> --destination <route>
 * [--param k=v]...} — drives the host's NavController (Navigation 2 /
 * Compose Navigation) to a destination. See
 * {@code mcp__dta__app_runtime command=navigate} for the JSON shape.
 *
 * <p>Navigation 3 (NavBackStack/NavKey) is not supported. Use
 * {@link OpenDeepLinkCommand} for destinations declaring intent-filters.</p>
 */
@Command(name = "navigate",
         mixinStandardHelpOptions = true,
         description = "Push a destination onto the host's NavController (Nav 2 / Compose Navigation).")
public class NavigateCommand extends AbstractRuntimeCommand {

    @Option(names = {"--destination"}, required = true,
            description = "Route template or literal route (e.g. 'topic/{topicId}' or 'login').")
    private String destination;

    @Option(names = {"-p", "--param"},
            description = "key=value pairs filling {placeholder} segments. Repeatable. Unused params become query args.")
    private List<String> rawParams;

    @Override
    protected String progressMessage() {
        return "Navigating " + packageName + " → " + destination + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination", destination);
        if (rawParams != null && !rawParams.isEmpty()) {
            Map<String, String> params = new HashMap<>();
            for (String p : rawParams) {
                int eq = p.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException(
                    "Bad --param '" + p + "' — expected key=value");
                params.put(p.substring(0, eq), p.substring(eq + 1));
            }
            body.put("params", params);
        }
        String json = new ObjectMapper().writeValueAsString(body);
        return daemon.navigate(packageName, deviceSerial, json);
    }
}
