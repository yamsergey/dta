package io.yamsergey.dta.tools.android.gradle;

import java.util.Optional;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import io.yamsergey.dta.tools.sugar.Result;
import lombok.Builder;

/**
 * Fetch {@link GradleProject} from {@link ProjectConnection} used to run this
 * action.
 **/
@Builder
public class FetchGradleProject implements BuildAction<Result<GradleProject>> {

  @Override
  public Result<GradleProject> execute(BuildController controller) {
    try {

      var maybeProject = Optional.ofNullable(controller.findModel(GradleProject.class));

      return maybeProject.map(project -> Result.<GradleProject>success()
          .value(project)
          .description(String.format("Gradle project successfully fetched from: %s", project.getProjectDirectory()))
          .build()
          .asResult()).orElse(Result.<GradleProject>failure()
              .description("There is no Gradle Project for provided connection.")
              .build()
              .asResult());
    } catch (Exception err) {
      return Result.<GradleProject>failure()
          .cause(err)
          .description("Error while fetching gradle project.")
          .build()
          .asResult();
    }
  }

}
