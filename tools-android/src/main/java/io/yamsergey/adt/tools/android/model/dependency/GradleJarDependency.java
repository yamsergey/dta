package io.yamsergey.adt.tools.android.model.dependency;

import lombok.Builder;

/**
 * Represents a JAR dependency from an external repository (Maven, Gradle, etc.)
 * with complete Maven coordinates.
 *
 * <p>Examples include:</p>
 * <ul>
 *   <li>Kotlin standard library: org.jetbrains.kotlin:kotlin-stdlib:1.9.0</li>
 *   <li>Google libraries: com.google.guava:guava:32.1.3-jre</li>
 *   <li>Other JAR dependencies from Maven Central, Google, etc.</li>
 * </ul>
 */
@Builder(toBuilder = true)
public record GradleJarDependency(
        String path,
        String groupId,
        String artifactId,
        String version,
        Scope scope) implements ExternalDependency {
}