package io.yamsergey.dta.tools.android.gradle;

import java.util.Optional;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidProject;

import io.yamsergey.dta.tools.sugar.Result;
import lombok.Builder;

/**
 * Retrieve {@link AndroidProject} from provided gardle model.
 */
@Builder
public class FetchAndroidProject implements BuildAction<Result<AndroidProject>> {

  private final GradleProject gradleProject;

  public FetchAndroidProject(GradleProject gradleProject) {
    this.gradleProject = gradleProject;
  }

  @Override
  public Result<AndroidProject> execute(BuildController controller) {
    try {

      var result = Optional.ofNullable(controller.findModel(gradleProject, AndroidProject.class));
      return result.map(project -> Result.<AndroidProject>success()
          .value(project)
          .build()
          .asResult())
          .orElse(Result.<AndroidProject>failure()
              .description(String.format("There is no android project in: %s", gradleProject.getPath()))
              .build()
              .asResult());

    } catch (Exception err) {
      return Result.<AndroidProject>failure()
          .cause(err)
          .description(
              String.format("There was an error while fetching Android Project model from: %s",
                  gradleProject.getPath()))
          .build()
          .asResult();
    }
  }
}
