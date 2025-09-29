package io.yamsergey.adt.tools.android.model.dependency;

/**
 * Represents dependencies from external repositories (Maven, Gradle, etc.)
 * that have Maven coordinates (groupId, artifactId, version).
 */
public sealed interface ExternalDependency extends Dependency
    permits GradleJarDependency, GradleAarDependency {

    String groupId();
    String artifactId();
    String version();
}