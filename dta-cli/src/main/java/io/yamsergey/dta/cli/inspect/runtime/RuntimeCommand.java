package io.yamsergey.dta.cli.inspect.runtime;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code dta-cli inspect runtime} — namespace for runtime introspection
 * commands. Each subcommand maps 1:1 to a {@code mcp__dta__app_runtime
 * command=…} operation so the CLI and MCP surfaces stay in lock-step.
 */
@Command(name = "runtime",
         mixinStandardHelpOptions = true,
         description = "Inspect app runtime state (ViewModels, navigation, lifecycle, memory, threads, AppFunctions).",
         subcommands = {
             AppFunctionsCommand.class,
             ViewModelsCommand.class,
             SavedStateCommand.class,
             NavigationBackstackCommand.class,
             NavigationGraphCommand.class,
             NavigateCommand.class,
             OpenDeepLinkCommand.class,
             LifecycleCommand.class,
             MemoryCommand.class,
             ThreadsCommand.class
         })
public class RuntimeCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
