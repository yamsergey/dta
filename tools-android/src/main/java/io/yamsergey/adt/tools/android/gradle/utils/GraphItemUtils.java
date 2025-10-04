package io.yamsergey.adt.tools.android.gradle.utils;

import java.util.Map;
import java.util.Optional;

import com.android.builder.model.v2.ide.GraphItem;
import com.android.builder.model.v2.ide.Library;

import io.yamsergey.adt.tools.android.model.dependency.AndroidProjectDependency;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.dependency.GenericProjectDependency;
import io.yamsergey.adt.tools.android.model.dependency.GradleAarDependency;
import io.yamsergey.adt.tools.android.model.dependency.GradleJarDependency;

public class GraphItemUtils {

  /**
   * Parses a GraphItem dependency object with filesystem path resolution.
   * Combines coordinate parsing with JAR file location discovery using the
   * VariantDependencies libraries map.
   */
  public static Optional<Dependency> resolveDependency(GraphItem graphItem, Dependency.Scope scope,
      Map<String, Library> librariesMap) {
    try {
      String str = graphItem.toString();

      // Extract the full key from the GraphItem
      int keyStart = str.indexOf("key=");
      if (keyStart == -1)
        return Optional.empty();

      keyStart += 4; // Skip "key="

      // Find the end of the key - it should be before ", requestedCoordinates="
      int keyEnd = str.indexOf(", requestedCoordinates=", keyStart);
      if (keyEnd == -1)
        return Optional.empty();

      String key = str.substring(keyStart, keyEnd);

      // Parse key: "group|name|version|attributes..."
      String[] parts = key.split("\\|");
      if (parts.length < 3)
        return Optional.empty();

      String group = parts[0];
      String name = parts[1];
      String version = parts[2];

      // Handle project dependencies (key format: ":|:module-name|...")
      if (":".equals(group) || group.isEmpty() || name.startsWith(":")) {
        return resolveProjectDependency(key, parts, scope, librariesMap);
      }

      // Try to find this dependency in the libraries map
      Library library = librariesMap.get(key);
      if (library != null) {

        // Extract JAR files based on library type
        if (library.getAndroidLibraryData() != null) {
          // Android Library (AAR) - has multiple JAR files
          return Optional.of(GradleAarDependency.builder()
              .path(library.getArtifact().getAbsolutePath())
              .groupId(group)
              .artifactId(name)
              .version(version)
              .resolvedJars(library.getAndroidLibraryData().getCompileJarFiles()
                  .stream()
                  .map(file -> file.getAbsolutePath()).toList())
              .scope(scope)
              .build());
        } else if (library.getArtifact() != null) {
          // Regular JAR dependency
          return Optional.of(GradleJarDependency.builder()
              .path(library.getArtifact().getAbsolutePath())
              .groupId(group)
              .artifactId(name)
              .version(version)
              .scope(scope)
              .build());
        }
      }
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Resolves a project dependency from the key parts and libraries map.
   * Determines whether it's an Android or Generic project dependency based on
   * the presence of buildType information.
   *
   * @param key the full key string
   * @param parts the key split by "|"
   * @param scope the dependency scope
   * @param librariesMap map of libraries for additional info
   * @return Optional of the resolved project dependency
   */
  private static Optional<Dependency> resolveProjectDependency(
      String key,
      String[] parts,
      Dependency.Scope scope,
      Map<String, Library> librariesMap) {

    String projectPath = parts[1]; // e.g., ":kotlin-android-library-one"

    // Try to find this dependency in the libraries map for detailed info
    Library library = librariesMap.get(key);

    if (library != null && library.getProjectInfo() != null) {
      var projectInfo = library.getProjectInfo();
      String buildType = projectInfo.getBuildType();
      String artifactPath = library.getArtifact() != null ? library.getArtifact().getAbsolutePath() : "";

      // If buildType is present, it's an Android project dependency
      if (buildType != null && !buildType.isEmpty()) {
        return Optional.of(AndroidProjectDependency.builder()
            .path(artifactPath)
            .projectPath(projectInfo.getProjectPath())
            .buildType(buildType)
            .capabilities(projectInfo.getCapabilities())
            .scope(scope)
            .build());
      } else {
        // Generic JVM project dependency
        return Optional.of(GenericProjectDependency.builder()
            .path(artifactPath)
            .projectPath(projectInfo.getProjectPath())
            .capabilities(projectInfo.getCapabilities())
            .scope(scope)
            .build());
      }
    }

    // Fallback: determine type from key structure
    // Android projects have buildType as 3rd part: ":|:module|debug|..."
    // Generic projects have attributes as 3rd part: ":|:module|org.gradle.category>library|..."
    if (parts.length > 2) {
      String thirdPart = parts[2];

      // Check if third part looks like a build type (simple string without ">" attribute marker)
      boolean isAndroidProject = !thirdPart.contains(">");

      if (isAndroidProject) {
        return Optional.of(AndroidProjectDependency.builder()
            .path("")
            .projectPath(projectPath)
            .buildType(thirdPart)
            .capabilities(java.util.List.of())
            .scope(scope)
            .build());
      } else {
        return Optional.of(GenericProjectDependency.builder()
            .path("")
            .projectPath(projectPath)
            .capabilities(java.util.List.of())
            .scope(scope)
            .build());
      }
    }

    // Last resort: return as generic project dependency
    return Optional.of(GenericProjectDependency.builder()
        .path("")
        .projectPath(projectPath)
        .capabilities(java.util.List.of())
        .scope(scope)
        .build());
  }
}
