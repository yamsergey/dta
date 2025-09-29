package io.yamsergey.adt.workspace.kotlin.cli;

import java.io.File;
import java.util.concurrent.Callable;

import io.yamsergey.adt.tools.android.model.project.Project;
import io.yamsergey.adt.tools.android.resolver.AndroidProjectResolver;
import io.yamsergey.adt.workspace.kotlin.converter.ProjectToWorkspaceConverter;
import io.yamsergey.adt.workspace.kotlin.model.Workspace;
import io.yamsergey.adt.workspace.kotlin.serializer.WorkspaceJsonSerializer;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "generate", description = "Generate Kotlin's workspace.json from Android project.")
public class GenerateCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "Android project directory path")
  private String projectPath = ".";

  @Option(names = { "--output" }, description = "Filepath where workspace.json will be stored.")
  private String outputFilePath = "workspace.json";

  @Override
  public Integer call() throws Exception {

    File projectDir = new File(projectPath);

    if (!projectDir.exists() || !projectDir.isDirectory()) {
      System.err.println("Error: Project directory does not exist: " + projectPath);
      return 1;
    }

    AndroidProjectResolver resolver = new AndroidProjectResolver(projectPath);
    var resolvedProjectResult = resolver.resolve();

    return switch (resolvedProjectResult) {
      case Success<Project> project -> {
        try {
          // Convert to workspace format
          ProjectToWorkspaceConverter converter = new ProjectToWorkspaceConverter();
          Workspace workspace = converter.convert(project.value());

          // Serialize to JSON
          WorkspaceJsonSerializer serializer = new WorkspaceJsonSerializer();
          File outputFile = new File(outputFilePath);
          serializer.toJsonFile(workspace, outputFile);

          System.out.println("Workspace.json generated successfully: " + outputFile.getAbsolutePath());
          yield 0;
        } catch (Exception e) {
          System.err.println("Error generating workspace.json: " + e.getMessage());
          e.printStackTrace();
          yield 1;
        }
      }
      case Failure<Project> failure -> {
        System.err.println("Failed to resolve Android project: " + failure.description());
        if (failure.cause() != null) {
          System.err.println("Cause: " + failure.cause().getMessage());
        }
        yield 1;
      }

      default -> {
        System.err.println(String.format("Unknown results for: %s", projectPath));
        yield 1;
      }
    };
  }
}