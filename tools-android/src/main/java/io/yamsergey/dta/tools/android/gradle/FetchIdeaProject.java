package io.yamsergey.dta.tools.android.gradle;

import java.util.Optional;

import javax.annotation.Nonnull;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.idea.IdeaProject;

import io.yamsergey.dta.tools.sugar.Result;
import lombok.Builder;

@Builder
public class FetchIdeaProject implements BuildAction<Result<IdeaProject>> {

  @Nonnull
  private final GradleProject project;

  public FetchIdeaProject(GradleProject project) {
    this.project = project;
  }

  @Override
  public Result<IdeaProject> execute(BuildController controller) {
    try {
      var maybeModel = Optional.ofNullable(controller.findModel(project, IdeaProject.class));

      return maybeModel
          .map(model -> Result.<IdeaProject>success()
              .value(model)
              .description(String
                  .format("Successfully fetched IdeaProject model for: %s", project.getProjectDirectory()))
              .build().asResult())
          .orElse(Result.<IdeaProject>failure()
              .description(String.format("Couldn't find IdeaProject model for: %s", project.getProjectDirectory()))
              .build().asResult());

    } catch (Exception err) {
      return Result.<IdeaProject>failure()
          .cause(err)
          .description(String.format("Error during fetching IdeaProject model for: %s", project.getProjectDirectory()))
          .build()
          .asResult();
    }
  }

}
