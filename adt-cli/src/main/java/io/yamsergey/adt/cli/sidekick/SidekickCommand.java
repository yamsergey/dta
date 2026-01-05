package io.yamsergey.adt.cli.sidekick;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI command for managing the ADT Sidekick agent on Android devices.
 *
 * <p>This command provides capabilities for attaching the sidekick JVMTI agent
 * to running applications with full class retransformation support.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Attach agent to an app (restarts the app with agent)
 * adt-cli sidekick attach com.example.myapp
 *
 * # Attach with specific device
 * adt-cli sidekick attach -d emulator-5554 com.example.myapp
 * </pre>
 */
@Command(name = "sidekick",
         mixinStandardHelpOptions = true,
         description = "Manage ADT Sidekick agent on Android devices.",
         subcommands = {
             AttachCommand.class
         })
public class SidekickCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
