package io.yamsergey.dta.cli.mock;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Parent command for mock rule operations.
 *
 * <p>Usage: dta-cli mock [subcommand]</p>
 */
@Command(name = "mock",
         mixinStandardHelpOptions = true,
         description = "Manage mock rules for HTTP and WebSocket traffic.",
         subcommands = {
             MockListCommand.class,
             MockCreateCommand.class,
             MockUpdateCommand.class,
             MockEnableCommand.class,
             MockDisableCommand.class,
             MockDeleteCommand.class,
             MockConfigCommand.class
         })
public class MockCommand implements Runnable {

    @Override
    public void run() {
        // Show help when run without subcommand
        CommandLine.usage(this, System.out);
    }
}
