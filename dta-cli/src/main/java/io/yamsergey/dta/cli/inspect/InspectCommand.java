package io.yamsergey.dta.cli.inspect;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI command for inspecting Android devices and applications.
 *
 * <p>This command provides various inspection capabilities for connected Android devices,
 * including UI hierarchy analysis and other device inspection features.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Dump UI layout hierarchy
 * dta-cli inspect layout -o hierarchy.xml
 *
 * # Dump from specific device
 * dta-cli inspect layout -d emulator-5554 -o hierarchy.xml
 * </pre>
 */
@Command(name = "inspect",
         mixinStandardHelpOptions = true,
         description = "Inspect Android devices and applications.",
         subcommands = {
             LayoutCommand.class,
             LayoutTreeCommand.class,
             ScreenshotCommand.class,
             ScrollScreenshotCommand.class,
             LogcatCommand.class,
             ComposeCommand.class,
             NetworkCommand.class,
             WebSocketCommand.class,
             CustomTabsCommand.class,
             PackagesCommand.class,
             LogPullCommand.class
         })
public class InspectCommand implements Runnable {

    @Override
    public void run() {
        // Show help when run without subcommand
        CommandLine.usage(this, System.out);
    }
}
