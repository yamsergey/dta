package io.yamsergey.dta.cli.inspect.runtime;

import io.yamsergey.dta.daemon.DaemonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dta-cli inspect runtime saved-state <package> --vm-id <id>} —
 * dumps the SavedStateHandle contents for a single ViewModel. The id is the
 * one returned by {@code dta-cli inspect runtime viewmodels} (Activity-
 * scoped uses the bare key; NavEntry-scoped uses {@code navEntry::…} form).
 */
@Command(name = "saved-state",
         mixinStandardHelpOptions = true,
         description = "Dump a ViewModel's SavedStateHandle by id.")
public class SavedStateCommand extends AbstractRuntimeCommand {

    @Option(names = {"--vm-id"}, required = true,
            description = "ViewModel id from the viewmodels listing.")
    private String viewModelId;

    @Override
    protected String progressMessage() {
        return "Fetching SavedStateHandle for " + viewModelId + "...";
    }

    @Override
    protected String fetch(DaemonClient daemon) {
        return daemon.viewModelSavedState(packageName, viewModelId, deviceSerial);
    }
}
