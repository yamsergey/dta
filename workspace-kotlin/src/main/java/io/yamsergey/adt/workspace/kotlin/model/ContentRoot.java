package io.yamsergey.adt.workspace.kotlin.model;

import java.util.Collection;

import lombok.Builder;
import lombok.Singular;

/**
 * Content root definition for a module.
 *
 * Defines the root directory and source roots for a module,
 * typically representing the module's base path and its source directories.
 */
@Builder(toBuilder = true)
public record ContentRoot(
    String path,
    @Singular Collection<String> excludedPatterns,
    @Singular Collection<String> excludedUrls,
    @Singular Collection<SourceRoot> sourceRoots) {
}
