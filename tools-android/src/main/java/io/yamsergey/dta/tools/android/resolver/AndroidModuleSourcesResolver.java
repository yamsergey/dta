package io.yamsergey.dta.tools.android.resolver;

import java.util.Collection;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.ide.SourceProvider;
import com.android.builder.model.v2.models.BasicAndroidProject;

import io.yamsergey.dta.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.dta.tools.android.model.SourceRoot;
import io.yamsergey.dta.tools.android.model.SourceRoot.Language;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import io.yamsergey.dta.tools.sugar.Failure;

/**
 * Resolve main source roots for given Android project.
 * This class assume that provided project is valid {@link BasicAndroidProject}
 * and doesn't do any
 * additional verification. If it's not valid project it will return empty list.
 **/
public class AndroidModuleSourcesResolver implements Resolver<Collection<SourceRoot>> {

  @Nonnull
  private final GradleProject project;
  @Nonnull
  private final ProjectConnection projectConnection;
  @Nonnull
  private final GradleProject gradleProject;

  /**
   *
   * @param project           {@link GradleProject} represents android module.
   * @param rootProject       {@link GradleProject} represents project root.
   * @param projectConnection {@link ProjectConnection} for root project.
   */
  public AndroidModuleSourcesResolver(
      GradleProject project,
      ProjectConnection projectConnection,
      GradleProject rootProject) {
    this.project = project;
    this.projectConnection = projectConnection;
    this.gradleProject = rootProject;
  }

  @Override
  public Result<Collection<SourceRoot>> resolve() {
    var basicAndroidProject = projectConnection
        .action(FetchBasicAndroidProject.builder().gradleProject(project).build())
        .run();

    return switch (basicAndroidProject) {
      case Success<BasicAndroidProject> project -> Result.<Collection<SourceRoot>>success()
          .value(extractRoots(project.value()))
          .build().asResult();
      case Failure<BasicAndroidProject> failure -> failure.<Collection<SourceRoot>>forward();
      default -> Result.<Collection<SourceRoot>>failure()
          .description(String.format("Couldn't resolve sources for Android Project: %s", project.getName())).build();
    };
  }

  /**
   * Extract source roots pointing to the project source code.
   * Android Projects can have Java and Kotlin sources.
   * They might point ot the same path.
   *
   * @param project {@link BasicAndroidProject}
   * @return Collection of {@link SourceRoot} of the project.
   **/
  private Collection<SourceRoot> extractRoots(BasicAndroidProject project) {
    SourceProvider sourceProvider = project.getMainSourceSet().getSourceProvider();
    var javaRoots = sourceProvider.getJavaDirectories().stream()
        .map(file -> SourceRoot.builder().language(Language.JAVA).path(file.getAbsolutePath()).build());
    var kotlinRoots = sourceProvider.getKotlinDirectories().stream()
        .map(file -> SourceRoot.builder().language(Language.KOTLIN).path(file.getAbsolutePath()).build());
    return Stream.concat(javaRoots, kotlinRoots).toList();
  }
}
