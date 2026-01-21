package io.yamsergey.dta.cli;

import io.yamsergey.dta.cli.drawable.DrawableCommand;
import io.yamsergey.dta.cli.inspect.InspectCommand;
import io.yamsergey.dta.cli.inspector.InspectorWebCommand;
import io.yamsergey.dta.cli.mcp.McpCommand;
import io.yamsergey.dta.cli.resolve.ResolveCommand;
import io.yamsergey.dta.cli.workspace.WorkspaceCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "dta-cli",
         mixinStandardHelpOptions = true,
         version = "dta-cli 1.0.0",
         description = "Development Tools for Android - Project analysis and workspace generation",
         subcommands = {
    ResolveCommand.class,
    WorkspaceCommand.class,
    DrawableCommand.class,
    InspectCommand.class,
    McpCommand.class,
    InspectorWebCommand.class
})
public class App implements Runnable {

  @Override
  public void run() {
    // Show help when run without subcommand
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new App()).execute(args);
    System.exit(exitCode);
  }
}
