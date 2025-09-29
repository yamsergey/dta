package io.yamsergey.adt.workspace.kotlin.model;

import java.util.Collection;

import lombok.Builder;

/**
 * Module definition in Kotlin workspace.
 *
 * Represents a single module (e.g., "app.main", "app.test") with its dependencies,
 * source roots, and other configuration.
 */
@Builder(toBuilder = true)
public record Module(
        String name,
        Collection<Dependency> dependencies,
        Collection<ContentRoot> contentRoots,
        Collection<Object> facets) {
}