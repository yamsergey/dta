package io.yamsergey.adt.tools.android.resolver;

import java.util.Collection;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.ide.ProjectType;
import com.android.builder.model.v2.models.AndroidProject;
import com.android.builder.model.v2.models.BasicAndroidProject;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.adt.tools.android.gradle.utils.VariantUtils;
import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.module.FailedModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedAndroidModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.model.variant.ResolvedVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * Specialized resolver for Android modules that handles the complex
 * Android-specific
 * resolution process including build variants, dependencies, and source roots.
 *
 * <p>
 * This resolver encapsulates all Android-specific logic including:
 * </p>
 * <ul>
 * <li>Fetching Android project model from Gradle Tooling API</li>
 * <li>Resolving all available build variants for the module</li>
 * <li>Selecting appropriate build variant using {@link VariantUtils}</li>
 * <li>Resolving source roots (both regular and generated sources)</li>
 * <li>Resolving complete dependency tree with transitive dependencies</li>
 * </ul>
 *
 * <p>
 * The resolution process follows a chain of specialized resolvers:
 * </p>
 * <ol>
 * <li>{@link BuildVariantsResolver} - Extracts available build variants</li>
 * <li>{@link AndroidModuleSourcesResolver} - Resolves source directories</li>
 * <li>{@link AndroidModuleDependencyResolver} - Resolves dependencies with
 * flattening</li>
 * </ol>
 *
 * @see BuildVariantsResolver
 * @see AndroidModuleSourcesResolver
 * @see AndroidModuleDependencyResolver
 */
public class AndroidModuleResolver {

  /**
   * Resolves an Android module with complete build variant, dependency, and
   * source information.
   *
   * @param subProject           the Android Gradle project to resolve
   * @param connection           the Gradle project connection
   * @param rootProject          the root project for context
   * @param selectedBuildVariant the build variant to resolve dependencies for
   * @return {@link ResolvedAndroidModule} with complete information or
   *         {@link FailedModule} on error
   */
  public ResolvedModule resolve(
      @Nonnull GradleProject subProject,
      @Nonnull ProjectConnection connection,
      @Nonnull GradleProject rootProject,
      @Nonnull BuildVariant selectedBuildVariant) {

    var androidProjectResult = connection.action(
        FetchAndroidProject.builder().gradleProject(subProject).build()).run();
    var basicProjectResult = connection.action(
        FetchBasicAndroidProject.builder().gradleProject(subProject).build()).run();

    return switch (androidProjectResult) {
      case Success<AndroidProject> success -> {
        var moduleBuildVariants = new BuildVariantsResolver(success.value()).resolve();

        yield switch (moduleBuildVariants) {
          case Success<Collection<BuildVariant>> variants -> {
            var resolvedModuleSources = new AndroidModuleSourcesResolver(
                subProject, connection, rootProject).resolve();
            var moduleBuildVariant = VariantUtils.chooseBuildVariant(
                selectedBuildVariant, variants.value());
            var resolvedVariantResult = new AndroidModuleDependencyResolver(
                moduleBuildVariant,
                subProject,
                connection,
                success.value()).resolve();

            yield switch (resolvedModuleSources) {
              case Success<Collection<SourceRoot>> sources -> switch (resolvedVariantResult) {
                case Success<ResolvedVariant> resolvedVariant -> {
                  // Determine module type from BasicAndroidProject
                  ResolvedAndroidModule.Type moduleType = switch (basicProjectResult) {
                    case Success<BasicAndroidProject> basicProject ->
                      basicProject.value().getProjectType() == ProjectType.APPLICATION
                          ? ResolvedAndroidModule.Type.APPLICATION
                          : ResolvedAndroidModule.Type.LIBRARY;
                    case Failure<BasicAndroidProject> failure -> ResolvedAndroidModule.Type.LIBRARY; // Default fallback
                    default -> ResolvedAndroidModule.Type.LIBRARY; // Default fallback
                  };

                  yield ResolvedAndroidModule.builder()
                      .name(subProject.getName())
                      .path(subProject.getProjectDirectory().toString())
                      .type(moduleType)
                      .selectedVariant(moduleBuildVariant)
                      .buildVariants(variants.value())
                      .dependencies(resolvedVariant.value().dependencies())
                      .roots(resolvedVariant.value().generatedRoots())
                      .roots(sources.value())
                      .build();
                }
                case Failure<ResolvedVariant> failure -> FailedModule.from(failure, subProject);
                default -> FailedModule.builder()
                    .name(subProject.getName())
                    .path(subProject.getPath())
                    .details("Unknown result of module variant resolution for: " + subProject.getPath())
                    .build();
              };
              case Failure<Collection<SourceRoot>> failure -> FailedModule.from(failure, subProject);
              default -> FailedModule.builder()
                  .name(subProject.getName())
                  .path(subProject.getPath())
                  .details("Unknown result for source set resolution for: " + subProject.getPath())
                  .build();
            };
          }
          case Failure<Collection<BuildVariant>> failure -> FailedModule.from(failure, subProject);
          default -> FailedModule.builder()
              .name(subProject.getName())
              .path(subProject.getPath())
              .details("Unknown build variant resolution result")
              .build();
        };
      }
      case Failure<AndroidProject> failure -> FailedModule.from(failure, subProject);
      default -> FailedModule.builder()
          .name(subProject.getName())
          .path(subProject.getPath())
          .details("Unknown android project resolution result")
          .build();
    };
  }
}
