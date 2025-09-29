package io.yamsergey.adt.workspace.kotlin.model;

import lombok.Builder;

/**
 * Kotlin-specific settings for a module.
 *
 * Contains Kotlin compiler configuration, module relationships,
 * and other Kotlin-specific metadata.
 */
@Builder(toBuilder = true)
public record KotlinSettings() {
}
