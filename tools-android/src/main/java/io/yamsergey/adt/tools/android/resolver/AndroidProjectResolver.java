package io.yamsergey.adt.tools.android.resolver;

import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.establishConnection;
import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.isAndroidProject;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.ide.ProjectType;
import com.android.builder.model.v2.models.AndroidDsl;
import com.android.builder.model.v2.models.AndroidProject;
import com.android.builder.model.v2.models.BasicAndroidProject;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidDsl;
import io.yamsergey.adt.tools.android.gradle.FetchAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchGradleProject;
import io.yamsergey.adt.tools.android.model.project.Project;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * Resolve an android project with dependencies. When no {@link BuildVariant}
 * selected default one will be used.
 **/
public class AndroidProjectResolver implements Resolver<Project> {

  @Nonnull
  private final File path;

  /**
   * @param path to project to be resolved.
   **/
  public AndroidProjectResolver(String path) {
    this.path = new File(path);
  }

  @Nullable
  private BuildVariant selectedBuildVariant;
  @Nullable
  private Collection<BuildVariant> buildVariants;

  private ProjectConnection connection;
  private GradleProject gradleProject;

  @Override
  public Result<Project> resolve() {
    if (selectedBuildVariant == null) {
      switch (resolveBuildVariants()) {
        case Success<Collection<BuildVariant>> success -> {
          Optional<BuildVariant> defaultVariant = selectDefaultVariant(success.value());

          if (defaultVariant.isEmpty()) {
            return Result.<Project>failure()
                .description(String.format("Couldn't choose default build variant from: %s", success.value()))
                .build()
                .asResult();
          }
          selectedBuildVariant = defaultVariant.get();
          buildVariants = success.value();
        }
        case Failure<Collection<BuildVariant>> failure -> {
          return failure.<Project>forward();
        }
        default -> {
          return Result.<Project>failure()
              .description(String.format("Couldn't resolve default build type for: %s", gradleProject.getPath()))
              .build()
              .asResult();
        }
      }
    }

    ModuleResolutionStrategy moduleResolutionStrategy = new DefaultModuleResolver();
    var modules = moduleResolutionStrategy.resolveModules(connection, gradleProject, selectedBuildVariant);

    return Result.<Project>success()
        .value(Project.builder()
            .name(gradleProject.getName())
            .path(gradleProject.getPath())
            .modules(modules)
            .build())
        .build().asResult();
  }

  /**
   * Resolve build variants for {@link ProjectType.APPLICATION} subproject.
   * This function make assumption that modern android application has one
   * application module and it's build variants dictate which build variants will
   * be selected for other modules.
   **/
  public Result<Collection<BuildVariant>> resolveBuildVariants() {
    var connectionPrerequisite = ensureGradleConenction();

    if (connectionPrerequisite instanceof Failure failure) {
      return Result.<Collection<BuildVariant>>failure()
          .description(failure.description())
          .cause(failure.cause())
          .build().asResult();
    }

    var gradleProjectPrerequisite = ensureGradleGradleProject();

    if (gradleProjectPrerequisite instanceof Failure failure) {
      return Result.<Collection<BuildVariant>>failure()
          .description(failure.description())
          .cause(failure.cause())
          .build().asResult();
    }

    /**
     * TODO: In multi-module projects there could be possibility that we don't have
     * Application project at top (e.g. Library Project).
     * Additionally it can be that project structure in non-linear way and some
     * modules might be organized in tree structure:
     * - group module
     * - Android module
     * - Library module
     * - Android module
     * - etc.
     **/
    return gradleProject.getChildren().stream().filter(subProject -> isAndroidProject(subProject))
        .filter(subProject -> {
          var projectWithType = connection
              .action(FetchBasicAndroidProject.builder().gradleProject(subProject).build()).run();
          return switch (projectWithType) {
            case Success<BasicAndroidProject> success -> success.value().getProjectType() == ProjectType.APPLICATION;
            case Failure<BasicAndroidProject> failure -> false;
            default -> false;
          };
        })
        .map(project -> {
          var androidProjectResult = connection.action(FetchAndroidProject
              .builder()
              .gradleProject(project)
              .build()).run();

          var androidDslResult = connection.action(FetchAndroidDsl
              .builder()
              .gradleProject(project)
              .build()).run();

          return switch (androidProjectResult) {
            case Success<AndroidProject> success -> {
              var dsl = androidDslResult instanceof Success<AndroidDsl> dslSuccess
                  ? dslSuccess.value()
                  : null;
              yield new BuildVariantsResolver(success.value(), dsl).resolve();
            }
            case Failure<AndroidProject> failure -> failure.<Collection<BuildVariant>>forward();
            default -> Result.<Collection<BuildVariant>>failure().build().asResult();
          };
        }).findFirst().orElse(Result.<Collection<BuildVariant>>failure()
            .description(
                String.format("Couldn't find build variants for: %s", gradleProject.getChildren()))
            .build()
            .asResult());

  }

  private Result<ProjectConnection> ensureGradleConenction() {
    if (connection == null) {
      return switch (establishConnection(path)) {
        case Success<ProjectConnection> connectionResult -> {
          this.connection = connectionResult.value();
          yield connectionResult.asResult();
        }
        case Failure<ProjectConnection> failure -> failure;
        default -> Result.<ProjectConnection>failure()
            .description(String.format("Unknown result on establishing connection for: %s", path))
            .build()
            .asResult();
      };

    } else {
      return Result.<ProjectConnection>success()
          .value(connection)
          .description(String.format("Gradle's connections already establised for %s", path.getAbsolutePath()))
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
            .description(String.format("Unknown result on fetching gradle project for: %s", path))
            .build()
            .asResult();
      };
    } else {
      return Result.<GradleProject>failure()
          .description(String.format(
              "Connection to gradle has to be eastablished first, before atttempt to resolve GradleProject for: %s",
              path.getAbsolutePath()))
          .build()
          .asResult();
    }
  }

  /**
   * Select default build variant following Android Studio's selection algorithm:
   * 1. First, check if any variant has isDefault flag set to true (from DSL)
   * 2. If none, prefer variants with "debug" build type, select alphabetically first
   * 3. If no debug variants, select alphabetically first overall
   *
   * @param variants collection of build variants to choose from
   * @return Optional containing the selected default variant, or empty if collection is empty
   */
  private Optional<BuildVariant> selectDefaultVariant(Collection<BuildVariant> variants) {
    if (variants == null || variants.isEmpty()) {
      return Optional.empty();
    }

    // Step 1: Check for explicitly marked default variant
    Optional<BuildVariant> explicitDefault = variants.stream()
        .filter(variant -> variant.isDefault() != null && variant.isDefault())
        .findFirst();

    if (explicitDefault.isPresent()) {
      return explicitDefault;
    }

    // Step 2: Prefer debug variants, select alphabetically first among them
    Optional<BuildVariant> debugVariant = variants.stream()
        .filter(variant -> variant.name().toLowerCase().contains("debug"))
        .min(Comparator.comparing(BuildVariant::name));

    if (debugVariant.isPresent()) {
      return debugVariant;
    }

    // Step 3: No debug variants, select alphabetically first overall
    return variants.stream()
        .min(Comparator.comparing(BuildVariant::name));
  }
}
