package io.yamsergey.adt.tools.android.model.module;

/**
 * Base interface for all resolved module types in an Android/Gradle project.
 * All implementations must provide name and path information.
 **/
public sealed interface ResolvedModule
    permits ResolvedAndroidModule, ResolvedGenericModule, UnknownModule, FailedModule {

    /**
     * @return the name of the module (e.g., "app", "core", "feature-auth")
     */
    String name();

    /**
     * @return the absolute filesystem path to the module directory
     */
    String path();
}
