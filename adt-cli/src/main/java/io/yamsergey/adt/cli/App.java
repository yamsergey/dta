package io.yamsergey.adt.cli;

import io.yamsergey.adt.cli.resolve.ResolveCommand;
import io.yamsergey.adt.cli.workspace.WorkspaceCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "adt-cli",
         mixinStandardHelpOptions = true,
         version = "adt-cli 1.0.0",
         description = "Android Development Tools - Project analysis and workspace generation",
         subcommands = {
    ResolveCommand.class,
    WorkspaceCommand.class
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
