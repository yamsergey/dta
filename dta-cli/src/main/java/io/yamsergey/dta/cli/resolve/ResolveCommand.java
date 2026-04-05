package io.yamsergey.dta.cli.resolve;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.ValueSerializerModifier;

import io.yamsergey.dta.cli.serialization.jackson.CompositeSerializerModifier;
import io.yamsergey.dta.cli.serialization.jackson.ParentIgnoreMixIn;
import io.yamsergey.dta.cli.serialization.jackson.ProjectMixIn;
import io.yamsergey.dta.cli.serialization.jackson.PropertyFilterSerializerModifier;
import io.yamsergey.dta.cli.serialization.jackson.SafeSerializerModifier;
import io.yamsergey.dta.cli.serialization.jackson.TaskMixIn;
import io.yamsergey.dta.tools.android.model.project.Project;
import io.yamsergey.dta.tools.android.model.variant.BuildVariant;
import io.yamsergey.dta.tools.android.resolver.AndroidProjectResolver;
import io.yamsergey.dta.tools.android.resolver.RawProjectResolver;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Success;
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

  @Option(names = { "--exclude" }, split = ",", description = "Comma-separated list of fields to exclude from output (e.g., tasks,dependencies). Mutually exclusive with --include.")
  private java.util.List<String> excludeFields = null;

  @Option(names = { "--include" }, split = ",", description = "Comma-separated list of fields to include in output (e.g., name,path). Excludes everything else. Mutually exclusive with --exclude.")
  private java.util.List<String> includeFields = null;

  @Override
  public Integer call() throws Exception {

    File projectDir = new File(projectPath);

    if (!projectDir.exists() || !projectDir.isDirectory()) {
      System.err.println("Error: Project directory does not exist: " + projectPath);
      return 1;
    }

    // Validate mutually exclusive options
    if (excludeFields != null && includeFields != null) {
      System.err.println("Error: --exclude and --include options are mutually exclusive. Use only one.");
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
      // Register custom serializer modifiers
      SimpleModule module = new SimpleModule();

      // Combine property filtering with safe serialization
      List<ValueSerializerModifier> modifiers = new ArrayList<>();

      // First apply property filtering (if configured)
      if (includeFields != null) {
        modifiers.add(PropertyFilterSerializerModifier.includeOnly(includeFields));
      } else if (excludeFields != null) {
        modifiers.add(PropertyFilterSerializerModifier.excludeFields(excludeFields));
      }

      // Then apply safe serialization to handle exceptions
      modifiers.add(new SafeSerializerModifier());

      // Use composite modifier
      module.setSerializerModifier(new CompositeSerializerModifier(modifiers));

      ObjectMapper mapper = JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .addModule(module)
          // Globally ignore 'parent' properties to break circular references
          .addMixIn(Object.class, ParentIgnoreMixIn.class)
          // Add mixins to handle circular references in Gradle objects
          .addMixIn(org.gradle.tooling.model.Task.class, TaskMixIn.class)
          .addMixIn(org.gradle.tooling.model.GradleProject.class, ProjectMixIn.class)
          .build();

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
