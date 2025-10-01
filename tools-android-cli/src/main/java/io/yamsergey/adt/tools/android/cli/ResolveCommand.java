package io.yamsergey.adt.tools.android.cli;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.yamsergey.adt.tools.android.cli.serialization.jackson.ParentIgnoreMixIn;
import io.yamsergey.adt.tools.android.cli.serialization.jackson.ProjectMixIn;
import io.yamsergey.adt.tools.android.cli.serialization.jackson.SafeSerializerModifier;
import io.yamsergey.adt.tools.android.cli.serialization.jackson.TaskMixIn;
import io.yamsergey.adt.tools.android.model.project.Project;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.resolver.AndroidProjectResolver;
import io.yamsergey.adt.tools.android.resolver.RawProjectResolver;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "resolve", description = "Resolve dependencies for all modules in the project.")
public class ResolveCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "Android project directory path")
  private String projectPath = ".";

  @Option(names = { "--workspace" }, description = "Print out project structure as JSON.")
  private boolean workspaceOutput = false;

  @Option(names = { "--variants" }, description = "Print out project build variants as JSON.")
  private boolean variantsOutput = false;

  @Option(names = { "--raw" }, description = "Output raw results for the project.")
  private boolean rawOutput = false;

  @Option(names = { "--output" }, description = "Filepath where output will be stored.")
  private String outputFilePath = null;

  @Override
  public Integer call() throws Exception {

    File projectDir = new File(projectPath);

    if (!projectDir.exists() || !projectDir.isDirectory()) {
      System.err.println("Error: Project directory does not exist: " + projectPath);
      return 1;
    }

    if (workspaceOutput) {

      AndroidProjectResolver resolver = new AndroidProjectResolver(projectPath);
      var resolvedProjectResult = resolver.resolve();

      return switch (resolvedProjectResult) {
        case Success<Project> project -> {
          outputAsJson(project.value());
          yield 0;
        }
        case Failure<Project> failure -> {

          outputAsJson(failure);
          yield 1;
        }

        default -> {
          System.out.println(String.format("Unknown results for: %s", projectPath));
          yield 1;
        }
      };
    } else if (variantsOutput) {
      AndroidProjectResolver resolver = new AndroidProjectResolver(projectPath);

      var resolvedVariantsReaault = resolver.resolveBuildVariants();

      return switch (resolvedVariantsReaault) {
        case Success<Collection<BuildVariant>> variants -> {
          outputAsJson(variants.value());
          yield 0;
        }
        case Failure<Collection<BuildVariant>> failure -> {
          outputAsJson(failure);
          yield 1;
        }
        default -> {
          System.out.println(String.format("Unknown results for: %s", projectPath));
          yield 1;
        }
      };
    } else if (rawOutput) {
      BuildVariant selectedBuildVariant = BuildVariant.builder().displayName("debug").name("debug").isDefault(true)
          .build();
      RawProjectResolver resolver = new RawProjectResolver(selectedBuildVariant, projectPath);

      outputAsJson(resolver.resolve());
      return 0;
    }

    System.out.println("No option selected. Please usee --help to find out options.");
    return 0;
  }

  private void outputAsJson(Object project) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      mapper.enable(SerializationFeature.INDENT_OUTPUT);
      mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

      // Register custom serializer modifier that handles unsupported methods gracefully
      SimpleModule module = new SimpleModule();
      module.setSerializerModifier(new SafeSerializerModifier());
      mapper.registerModule(module);

      // Globally ignore 'parent' properties to break circular references
      mapper.addMixIn(Object.class, ParentIgnoreMixIn.class);

      // Add mixins to handle circular references in Gradle objects
      mapper.addMixIn(org.gradle.tooling.model.Task.class, TaskMixIn.class);
      mapper.addMixIn(org.gradle.tooling.model.GradleProject.class, ProjectMixIn.class);

      if (outputFilePath != null) {
        File outputFile = new File(outputFilePath);
        mapper.writeValue(outputFile, project);
        System.out.print(String.format("Result saved to: %s", outputFile.getAbsolutePath()));
      } else {
        String json = mapper.writeValueAsString(project);
        System.out.println(json);
      }
    } catch (Exception e) {
      System.err.println("Error serializing to JSON: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
