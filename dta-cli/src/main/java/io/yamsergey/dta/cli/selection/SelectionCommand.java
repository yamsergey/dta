package io.yamsergey.dta.cli.selection;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Parent command for selection operations (multi-selection of elements, requests, messages).
 *
 * <p>Usage: dta-cli selection [subcommand]</p>
 */
@Command(name = "selection",
         mixinStandardHelpOptions = true,
         description = "Manage selections of UI elements, network requests, and WebSocket messages.",
         subcommands = {
             SelectionListCommand.class,
             SelectionAddCommand.class,
             SelectionRemoveCommand.class,
             SelectionClearCommand.class
         })
public class SelectionCommand implements Runnable {

    @Override
    public void run() {
        // Show help when run without subcommand
        CommandLine.usage(this, System.out);
    }
}
