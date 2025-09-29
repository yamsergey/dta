package io.yamsergey.adt.tools.android.gradle;

import java.util.Map;
import java.util.Optional;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.ModelBuilderParameter;
import com.android.builder.model.v2.models.VariantDependencies;

import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.sugar.Result;
import lombok.Builder;

/**
 * Gradle's {@link BuildAction} which will resolce {@link VariantDependencies}
 * for given project (module) and {@link BuildVariant};
 */
@Builder
public class FetchAndroidDependencies implements BuildAction<Result<VariantDependencies>> {

  private final GradleProject project;
  private final BuildVariant buildVariant;

  public FetchAndroidDependencies(GradleProject project, BuildVariant buildVariant) {
    this.project = project;
    this.buildVariant = buildVariant;
  }

  @Override
  public Result<VariantDependencies> execute(BuildController controller) {
    try {

      var maybeDependencies = controller.findModel(
          project,
          VariantDependencies.class,
          ModelBuilderParameter.class,
          modelBuilderParameter -> {
            modelBuilderParameter.setDontBuildAndroidTestRuntimeClasspath(false);
            modelBuilderParameter.setDontBuildRuntimeClasspath(false);
            modelBuilderParameter.setDontBuildScreenshotTestRuntimeClasspath(false);
            modelBuilderParameter.setDontBuildTestFixtureRuntimeClasspath(false);
            modelBuilderParameter.setDontBuildUnitTestRuntimeClasspath(false);
            modelBuilderParameter.setDontBuildHostTestRuntimeClasspath(Map.of());
            modelBuilderParameter.setAdditionalArtifactsInModel(true);
            modelBuilderParameter.setVariantName(buildVariant.name());
          });

      return Optional.ofNullable(maybeDependencies)
          .map(dependencies -> Result.<VariantDependencies>success().value(dependencies).build().asResult())
          .orElse(Result.<VariantDependencies>failure()
              .description(String.format("Could't resolve variant dependencies for: %s", project.getProjectDirectory()))
              .build()
              .asResult());
    } catch (Exception error) {
      return Result.<VariantDependencies>failure().cause(error)
          .description(
              String.format("Error during variant dependencies resolution for: %s", project.getProjectDirectory()))
          .build().asResult();
    }
  }
}
