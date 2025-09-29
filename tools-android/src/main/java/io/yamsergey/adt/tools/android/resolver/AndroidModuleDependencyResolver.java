package io.yamsergey.adt.tools.android.resolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.build.gradle.internal.cxx.cmake.TargetDataItem.Dependencies;
import com.android.builder.model.v2.ide.GraphItem;
import com.android.builder.model.v2.ide.Library;
import com.android.builder.model.v2.models.AndroidProject;
import com.android.builder.model.v2.models.BasicAndroidProject;
import com.android.builder.model.v2.models.VariantDependencies;
import com.android.tools.r8.internal.su;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidDependencies;
import io.yamsergey.adt.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.adt.tools.android.gradle.utils.GraphItemUtils;
import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.SourceRoot.Language;
import io.yamsergey.adt.tools.android.model.dependency.ClassFolderDependency;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.dependency.LocalJarDependency;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.model.variant.ResolvedVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * This class will resolve dependncies for provided module and selected build
 * variant. Android project dependencies highly depends on selected variant for
 * the build.
 **/
public class AndroidModuleDependencyResolver implements Resolver<ResolvedVariant> {
  @Nonnull
  private final BuildVariant selectedVariant;
  @Nonnull
  private final GradleProject gradleProject;
  @Nonnull
  private final ProjectConnection projectConnection;
  @Nonnull
  private final AndroidProject androidProject;

  /**
   * @param selectedVariant   {@link BuildVariant} build variant to resolve.
   * @param androidProject    {@link AndroidProject} android representation of the
   *                          project.
   * @param gradleProject     {@link GradleProject} gradle representation of the
   *                          project.
   * @param projectConnection {@link ProjectConnection} for root project.
   */
  public AndroidModuleDependencyResolver(BuildVariant selectedVariant, GradleProject gradleProject,
      ProjectConnection projectConnection, AndroidProject androidProject) {
    this.selectedVariant = selectedVariant;
    this.gradleProject = gradleProject;
    this.projectConnection = projectConnection;
    this.androidProject = androidProject;
  }

  @Override
  public Result<ResolvedVariant> resolve() {
    var variantDependencies = projectConnection
        .action(FetchAndroidDependencies.builder()
            .buildVariant(selectedVariant)
            .project(gradleProject)
            .build())
        .run();

    return switch (variantDependencies) {
      case Success<VariantDependencies> dependencies -> {
        Collection<Dependency> allDependencies = new ArrayList<>();
        allDependencies.addAll(extractDependencies(dependencies.value()));
        allDependencies.addAll(extractClassesFolderDependencies());
        allDependencies.addAll(extractAndroidJar());

        yield Result.<ResolvedVariant>success()
            .value(ResolvedVariant.builder()
                .dependencies(allDependencies)
                .generatedRoots(extractRoots())
                .build())
            .build()
            .asResult();
      }
      case Failure<VariantDependencies> failure -> failure.<ResolvedVariant>forward();
      default -> Result.<ResolvedVariant>failure()
          .description(String.format("Unknown result of variant dependencies resolution for: %s , with variant: %s",
              androidProject, selectedVariant.displayName()))
          .build()
          .asResult();
    };
  }

  /**
   * Extract project dependencies. {@link VariantDependencies} contains list of
   * all libraries for the project
   * and dependencies graph for the variant. This function will parse
   * {@link GraphItem} from compile Dependencies recursively to flatten the entire
   * dependency tree and try to find path to resolved jars in provided libraries
   * list.
   * There are two types of dependencies in the result: {@link JarDependency} ,
   * {@link AarDependency}.
   *
   * @param dependencies dependencies for resolving.
   * @return collection of resolved dependencies for provided variant with all
   *         transitive dependencies flattened.
   **/
  private Collection<Dependency> extractDependencies(VariantDependencies dependencies) {
    var libraries = dependencies.getLibraries();

    return dependencies.getMainArtifact().getCompileDependencies()
        .parallelStream()
        .flatMap(rootItem -> flattenGraphItemRecursively(rootItem, libraries))
        .collect(Collectors.toSet());
  }

  private Collection<Dependency> extractAndroidJar() {
    var basicProjectResult = projectConnection.action(
        FetchBasicAndroidProject.builder().gradleProject(gradleProject).build()).run();
    return switch (basicProjectResult) {
      case Success<BasicAndroidProject> success -> success.value().getBootClasspath().stream()
          .<Dependency>map(
              path -> LocalJarDependency.builder().path(path.getAbsolutePath()).scope(Dependency.Scope.COMPILE)
                  .description("Boot Classpath: " + path.getName()).build())
          .toList();
      default -> List.of();
    };
  }

  /**
   * Recursively flatten GraphItem dependency tree to extract all transitive
   * dependencies.
   * Returns a stream of dependencies that can be processed in parallel.
   *
   * @param graphItem the current GraphItem to process
   * @param libraries map of library definitions for resolving dependencies
   * @return stream of dependencies from this GraphItem and all its children
   */
  private Stream<Dependency> flattenGraphItemRecursively(
      GraphItem graphItem,
      Map<String, Library> libraries) {

    // Create stream for current item
    Stream<Dependency> currentStream = GraphItemUtils
        .resolveDependency(graphItem, Dependency.Scope.COMPILE, libraries)
        .map(Stream::of)
        .orElse(Stream.empty());

    // Create stream for all children
    Stream<Dependency> childrenStream = graphItem.getDependencies()
        .parallelStream()
        .flatMap(child -> flattenGraphItemRecursively(child, libraries));

    // Combine current item and children streams
    return Stream.concat(currentStream, childrenStream);
  }

  /**
   * Extract dependencies from classesFolders which include compiled output
   * directories
   * and R.jar files. Distinguishes between JAR files (treated as JarDependency)
   * and
   * class directories (treated as ClassFolderDependency).
   *
   * @return collection of dependencies from classesFolders
   */
  private Collection<Dependency> extractClassesFolderDependencies() {
    var variant = androidProject.getVariants().stream()
        .filter(item -> item.getName().equals(selectedVariant.name()))
        .findFirst().get();
    var mainArtifact = variant.getMainArtifact();

    return mainArtifact.getClassesFolders().stream()
        .<Dependency>map(file -> {
          String path = file.getAbsolutePath();
          if (path.endsWith(".jar")) {
            // R.jar and other JAR files should be LocalJarDependency
            return LocalJarDependency.builder()
                .path(path)
                .description("Android compiled artifact: " + file.getName())
                .scope(Dependency.Scope.COMPILE)
                .build();
          } else {
            // Class directories should be ClassFolderDependency
            return ClassFolderDependency.builder()
                .path(path)
                .description("Compiled classes: " + file.getName())
                .scope(Dependency.Scope.COMPILE)
                .build();
          }
        })
        .toList();
  }

  /**
   * Extract source roots pointing to generated source files.
   *
   * @return collection of {@link SourceRoot} of the project.
   **/
  private Collection<SourceRoot> extractRoots() {
    var variant = androidProject.getVariants().stream().filter(item -> item.getName().equals(selectedVariant.name()))
        .findFirst().get();
    var mainArtifact = variant.getMainArtifact();
    var generatedRoots = mainArtifact.getGeneratedSourceFolders().stream()
        .map(file -> SourceRoot.builder().language(Language.JAVA).path(file.getAbsolutePath()).build()).toList();
    return generatedRoots;
  }

}
