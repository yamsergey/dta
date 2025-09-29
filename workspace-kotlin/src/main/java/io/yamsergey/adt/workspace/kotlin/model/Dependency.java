package io.yamsergey.adt.workspace.kotlin.model;

import lombok.Builder;

/**
 * Dependency definition for a module.
 *
 * Represents various types of dependencies including:
 * - library: External library dependency
 * - module: Dependency on another module in the workspace
 * - moduleSource: Source dependency on another module
 * - inheritedSdk: SDK inheritance
 */
@Builder(toBuilder = true)
public record Dependency(
        DependencyType type,
        String name,
        DependencyScope scope) {

    public enum DependencyType {
        library,
        module,
        moduleSource,
        inheritedSdk
    }

    public enum DependencyScope {
        compile,
        runtime,
        test,
        provided
    }
}