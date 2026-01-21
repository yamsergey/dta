package io.yamsergey.dta.tools.android.gradle;

import java.util.Optional;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.BasicAndroidProject;

import io.yamsergey.dta.tools.sugar.Result;
import lombok.Builder;

@Builder
public class FetchBasicAndroidProject implements BuildAction<Result<BasicAndroidProject>> {

  private final GradleProject gradleProject;

  public FetchBasicAndroidProject(GradleProject gradleProject) {
    this.gradleProject = gradleProject;
  }

  @Override
  public Result<BasicAndroidProject> execute(BuildController controller) {
    try {
      var nullableResult = Optional.ofNullable(controller.findModel(gradleProject, BasicAndroidProject.class));
      return nullableResult
          .map(project -> Result.<BasicAndroidProject>success()
              .value(project)
              .description(String.format("Successfully fetch BasicAndroiProject form: %s", gradleProject.getPath()))
              .build()
              .asResult())
          .orElse(Result.<BasicAndroidProject>failure()
              .description(String.format("There is not BasicAndroidProject model in: %s", gradleProject.getPath()))
              .build()
              .asResult());
    } catch (Exception e) {
      return Result.<BasicAndroidProject>failure()
          .cause(e)
          .description(
              String.format("Error happend during fetching BasicAndroidProject for: %s", gradleProject.getPath()))
          .build()
          .asResult();
    }
  }

}
