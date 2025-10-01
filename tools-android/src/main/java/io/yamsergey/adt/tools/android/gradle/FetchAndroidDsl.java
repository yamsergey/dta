package io.yamsergey.adt.tools.android.gradle;

import java.util.Optional;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidDsl;

import io.yamsergey.adt.tools.sugar.Result;
import lombok.Builder;

/**
 * Retrieve {@link AndroidDsl} from provided gradle model.
 * AndroidDsl contains build configuration including product flavors with isDefault flags.
 */
@Builder
public class FetchAndroidDsl implements BuildAction<Result<AndroidDsl>> {

  private final GradleProject gradleProject;

  public FetchAndroidDsl(GradleProject gradleProject) {
    this.gradleProject = gradleProject;
  }

  @Override
  public Result<AndroidDsl> execute(BuildController controller) {
    try {
      var result = Optional.ofNullable(controller.findModel(gradleProject, AndroidDsl.class));
      return result.map(dsl -> Result.<AndroidDsl>success()
          .value(dsl)
          .build()
          .asResult())
          .orElse(Result.<AndroidDsl>failure()
              .description(String.format("There is no Android DSL in: %s", gradleProject.getPath()))
              .build()
              .asResult());

    } catch (Exception err) {
      return Result.<AndroidDsl>failure()
          .cause(err)
          .description(
              String.format("There was an error while fetching Android DSL model from: %s",
                  gradleProject.getPath()))
          .build()
          .asResult();
    }
  }
}