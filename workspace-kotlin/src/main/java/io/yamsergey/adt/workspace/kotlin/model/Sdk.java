package io.yamsergey.adt.workspace.kotlin.model;

import lombok.Builder;

/**
 * SDK definition for the workspace.
 *
 * Represents SDK configuration, typically for Java/Kotlin SDKs.
 * Often empty in workspace configurations.
 */
@Builder(toBuilder = true)
public record Sdk(
        String name,
        String type,
        String version,
        String homePath) {
}