package io.yamsergey.adt.workspace.kotlin.model;

import java.util.Collection;
import java.util.List;

import lombok.Builder;

/**
 * Root workspace configuration for Kotlin LSP.
 *
 * Represents the complete workspace.json structure that defines modules,
 * dependencies, libraries, and Kotlin-specific settings for an IDE workspace.
 */
@Builder(toBuilder = true)
public record Workspace(
        Collection<Module> modules,
        Collection<Library> libraries,
        Collection<Sdk> sdks,
        Collection<KotlinSettings> kotlinSettings) {
}