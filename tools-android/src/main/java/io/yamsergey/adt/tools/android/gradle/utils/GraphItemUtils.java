package io.yamsergey.adt.tools.android.gradle.utils;

import java.util.Map;
import java.util.Optional;

import com.android.builder.model.v2.ide.GraphItem;
import com.android.builder.model.v2.ide.Library;

import io.yamsergey.adt.tools.android.model.dependency.Dependency;
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
        return null;

      keyStart += 4; // Skip "key="

      // Find the end of the key - it should be before ", requestedCoordinates="
      int keyEnd = str.indexOf(", requestedCoordinates=", keyStart);
      if (keyEnd == -1)
        return null;

      String key = str.substring(keyStart, keyEnd);

      // Parse key: "group|name|version|attributes..."
      String[] parts = key.split("\\|");
      if (parts.length < 3)
        return null;

      String group = parts[0];
      String name = parts[1];
      String version = parts[2];

      // Skip project dependencies
      if (":".equals(group) || group.isEmpty() || name.startsWith(":")) {
        return null;
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
}
