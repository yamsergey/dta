package io.yamsergey.dta.tools.android.gradle.utils;

import java.io.File;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import io.yamsergey.dta.tools.sugar.Result;

public class GradleProjectUtils {

  /**
   * Check if provided {@link GradleProject} is an Android Project.
   *
   * @param project {@link GradleProject} to check.
   *
   * @return true if provided project is an android project.
   **/
  public static boolean isAndroidProject(GradleProject project) {
    return new File(((GradleProject) project).getProjectDirectory(), "src/main/AndroidManifest.xml").exists();
  }

  /**
   * Create connection with Gradle project under provided path.
   *
   * @param path project directory.
   * @return {@link Result} with {@link ProjectConnection}.
   **/
  public static Result<ProjectConnection> establishConnection(File path) {
    try {
      var connection = GradleConnector.newConnector()
          .forProjectDirectory(path)
          .connect();
      return Result.<ProjectConnection>success()
          .value(connection)
          .description(String.format("Gradle's connection established for: %s", path.getAbsolutePath())).build()
          .asResult();
    } catch (Exception err) {
      return Result.<ProjectConnection>failure()
          .cause(err)
          .description(String.format("Couldn't establish connection with Gradle for: %s", path.getAbsolutePath()))
          .build()
          .asResult();
    }

  }
}
