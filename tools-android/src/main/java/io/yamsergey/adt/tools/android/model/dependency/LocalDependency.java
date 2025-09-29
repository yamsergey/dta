package io.yamsergey.adt.tools.android.model.dependency;

/**
 * Represents local build artifacts (compiled classes, R.jar, etc.)
 * that are produced during the build process.
 */
public sealed interface LocalDependency extends Dependency
    permits ClassFolderDependency, LocalJarDependency {

    String description();
}