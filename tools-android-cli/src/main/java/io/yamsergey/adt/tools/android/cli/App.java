package io.yamsergey.adt.tools.android.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "android-tools", mixinStandardHelpOptions = true, version = "android-tools 1.0", description = "Android development tools CLI", subcommands = {
    ResolveCommand.class,
    DrawableCommand.class
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
