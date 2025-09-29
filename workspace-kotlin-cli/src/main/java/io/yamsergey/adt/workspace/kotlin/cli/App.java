package io.yamsergey.adt.workspace.kotlin.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "workspace-kotlin",
         mixinStandardHelpOptions = true,
         version = "workspace-kotlin 1.0",
         description = "Generate Kotlin's workspace.json",
         subcommands = {
    GenerateCommand.class
})
public class App implements Runnable {

  @Override
  public void run() {
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new App()).execute(args);
    System.exit(exitCode);
  }
}
