package io.yamsergey.dta.cli;

import io.yamsergey.dta.cli.drawable.DrawableCommand;
import io.yamsergey.dta.cli.inspect.InspectCommand;
import io.yamsergey.dta.cli.inspector.InspectorWebCommand;
import io.yamsergey.dta.cli.mcp.McpCommand;
import io.yamsergey.dta.cli.mock.MockCommand;
import io.yamsergey.dta.cli.resolve.ResolveCommand;
import io.yamsergey.dta.cli.selection.SelectionCommand;
import io.yamsergey.dta.cli.workspace.WorkspaceCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "dta-cli",
         mixinStandardHelpOptions = true,
         versionProvider = App.VersionProvider.class,
         description = "Development Tools for Android - Project analysis and workspace generation",
         subcommands = {
    ResolveCommand.class,
    WorkspaceCommand.class,
    DrawableCommand.class,
    InspectCommand.class,
    MockCommand.class,
    SelectionCommand.class,
    McpCommand.class,
    InspectorWebCommand.class
})
public class App implements Runnable {

  private static final String VERSION = loadVersion();

  private static String loadVersion() {
    try (var is = App.class.getResourceAsStream("/version.properties")) {
      if (is != null) {
        var props = new java.util.Properties();
        props.load(is);
        return props.getProperty("version", "unknown");
      }
    } catch (Exception e) {
      // Ignore
    }
    return "unknown";
  }

  public static String getVersion() {
    return VERSION;
  }

  public static class VersionProvider implements picocli.CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[]{"dta-cli " + VERSION};
    }
  }

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
