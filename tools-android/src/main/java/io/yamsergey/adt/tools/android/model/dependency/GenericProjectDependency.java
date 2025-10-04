package io.yamsergey.adt.tools.android.model.dependency;

import java.util.List;

import lombok.Builder;

/**
 * Represents a dependency on a generic JVM/Kotlin Gradle module within the same project.
 *
 * <p>Generic project dependencies are not variant-specific and represent standard
 * JVM or Kotlin library modules without Android-specific build configurations.</p>
 *
 * <p>Key format example:</p>
 * <pre>
 * :|:kotlin-generic-library-one|org.gradle.category>library,
 * org.gradle.dependency.bundling>external,
 * org.gradle.jvm.environment>standard-jvm,
 * org.gradle.jvm.version>11,
 * org.gradle.libraryelements>jar,
 * org.gradle.usage>java-api,
 * org.jetbrains.kotlin.platform.type>jvm|Multi-Module-Kotlin-Compose:kotlin-generic-library-one:unspecified
 * </pre>
 *
 * <p>Key components:</p>
 * <ul>
 *   <li>Build ID: ":" (root build)</li>
 *   <li>Project path: ":kotlin-generic-library-one"</li>
 *   <li>No build type (not variant-specific)</li>
 *   <li>Attributes: JVM-specific Gradle attributes</li>
 *   <li>Capabilities: What this component provides. Typically contains a single element with
 *       Maven-style coordinates (e.g., "Multi-Module-Kotlin-Compose:kotlin-generic-library-one:unspecified").
 *       Used by Gradle for conflict resolution and feature variants.</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>implementation(project(":shared-utils"))</li>
 *   <li>implementation(project(":common"))</li>
 * </ul>
 *
 * @param path Artifact path (typically points to the built JAR file)
 * @param projectPath Gradle project path (e.g., ":kotlin-generic-library-one")
 * @param capabilities Gradle capabilities - what this component provides. Typically contains
 *                     a single element with Maven-style coordinates (e.g., "Multi-Module-Kotlin-Compose:kotlin-generic-library-one:unspecified").
 *                     Used by Gradle for conflict resolution and feature variants.
 * @param scope Dependency scope (COMPILE, TEST, RUNTIME)
 */
@Builder(toBuilder = true)
public record GenericProjectDependency(
        String path,
        String projectPath,
        List<String> capabilities,
        Scope scope) implements ProjectDependency {
}
