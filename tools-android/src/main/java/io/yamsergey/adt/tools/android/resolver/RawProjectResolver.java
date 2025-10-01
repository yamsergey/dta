package io.yamsergey.adt.tools.android.resolver;

import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.establishConnection;
import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.isAndroidProject;
import static io.yamsergey.adt.tools.android.gradle.utils.VariantUtils.chooseBuildVariant;
import static io.yamsergey.adt.tools.android.gradle.utils.VariantUtils.mapToBuildVariant;

import java.io.File;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidDsl;
import com.android.builder.model.v2.models.AndroidProject;
import com.android.builder.model.v2.models.VariantDependencies;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidDependencies;
import io.yamsergey.adt.tools.android.gradle.FetchAndroidDsl;
import io.yamsergey.adt.tools.android.gradle.FetchAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchGradleProject;
import io.yamsergey.adt.tools.android.gradle.FetchIdeaProject;
import io.yamsergey.adt.tools.android.model.module.RawAndroidModule;
import io.yamsergey.adt.tools.android.model.module.RawGenericModule;
import io.yamsergey.adt.tools.android.model.module.RawModule;
import io.yamsergey.adt.tools.android.model.project.RawProject;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * This class fetch Gradle's structure for the project and each module without
 * additional processing.
 **/
public class RawProjectResolver implements Resolver<RawProject> {
  private ProjectConnection connection;
  private GradleProject gradleProject;

  @Nonnull
  private final File projectPath;
  @Nonnull
  private final BuildVariant selectedBuildVariant;

  public RawProjectResolver(BuildVariant selectedBuildVariant, File projectPath) {
    this.selectedBuildVariant = selectedBuildVariant;
    this.projectPath = projectPath;
  }

  public RawProjectResolver(BuildVariant selectedBuildVariant, String projectPath) {
    this(selectedBuildVariant, new File(projectPath));
  }

  @Override
  public Result<RawProject> resolve() {
    var connectionPrerequisite = ensureGradleConenction();

    if (connectionPrerequisite instanceof Failure failure) {
      return Result.<RawProject>failure()
          .description(failure.description())
          .cause(failure.cause())
          .build().asResult();
    }

    var gradleProjectPrerequisite = ensureGradleGradleProject();

    if (gradleProjectPrerequisite instanceof Failure failure) {
      return Result.<RawProject>failure()
          .description(failure.description())
          .cause(failure.cause())
          .build().asResult();
    }

    return Result.<RawProject>success()
        .value(RawProject.builder()
            .module(map(gradleProject))
            .build())
        .build().asResult();
  }

  private RawModule map(GradleProject gradleProject) {
    if (isAndroidProject(gradleProject)) {
      var basicAndroidProject = connection
          .action(FetchBasicAndroidProject.builder()
              .gradleProject(gradleProject)
              .build())
          .run();
      var androidProject = connection
          .action(FetchAndroidProject.builder()
              .gradleProject(gradleProject)
              .build())
          .run();
      var androidDsl = connection
          .action(FetchAndroidDsl.builder()
              .gradleProject(gradleProject)
              .build())
          .run();

      var variantDependencies = switch (androidProject) {
        case Success<AndroidProject> project -> {
          yield connection.action(FetchAndroidDependencies.builder()
              .project(gradleProject)
              .buildVariant(chooseBuildVariant(selectedBuildVariant,
                  project.value().getVariants().stream().map(variant -> mapToBuildVariant(variant)).toList()))
              .build()).run();
        }
        case Failure<AndroidProject> failure -> failure.<VariantDependencies>forward();
      };

      var children = gradleProject.getChildren().stream().map(child -> map(child)).toList();

      return RawAndroidModule.builder()
          .project(gradleProject)
          .androidProject(androidProject)
          .androidDsl(androidDsl)
          .basicAndroidProject(basicAndroidProject)
          .variantDependencies(variantDependencies)
          .children(children)
          .build();
    } else {
      var ideaProject = connection.action(FetchIdeaProject.builder().project(gradleProject).build()).run();
      return RawGenericModule.builder()
          .project(gradleProject)
          .ideaProject(ideaProject)
          .children(gradleProject.getChildren().stream().map(child -> map(child)).toList())
          .build();
    }
  }

  private Result<ProjectConnection> ensureGradleConenction() {
    if (connection == null) {
      return switch (establishConnection(projectPath)) {
        case Success<ProjectConnection> connectionResult -> {
          this.connection = connectionResult.value();
          yield connectionResult.asResult();
        }
        case Failure<ProjectConnection> failure -> failure;
        default -> Result.<ProjectConnection>failure()
            .description(String.format("Unknown result on establishing connection for: %s", projectPath))
            .build()
            .asResult();
      };

    } else {
      return Result.<ProjectConnection>success()
          .value(connection)
          .description(String.format("Gradle's connections already establised for %s", projectPath.getAbsolutePath()))
          .build()
          .asResult();
    }
  }

  private Result<GradleProject> ensureGradleGradleProject() {
    if (connection != null) {
      return switch (connection.action(FetchGradleProject.builder().build()).run()) {
        case Success<GradleProject> project -> {
          gradleProject = project.value();
          yield project;
        }
        case Failure<GradleProject> failure -> failure;
        default -> Result.<GradleProject>failure()
            .description(String.format("Unknown result on fetching gradle project for: %s", projectPath))
            .build()
            .asResult();
      };
    } else {
      return Result.<GradleProject>failure()
          .description(String.format(
              "Connection to gradle has to be eastablished first, before atttempt to resolve GradleProject for: %s",
              projectPath.getAbsolutePath()))
          .build()
          .asResult();
    }
  }
}
