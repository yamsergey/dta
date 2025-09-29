package io.yamsergey.adt.tools.android.model.dependency;

import lombok.Builder;

/**
 * Represents a local JAR dependency that is produced during the build process.
 *
 * <p>These are JAR files generated as part of the Android build, typically including:</p>
 * <ul>
 *   <li>R.jar - Generated resource identifiers</li>
 *   <li>Other build-generated JAR files</li>
 * </ul>
 *
 * <p>Unlike external dependencies, these do not have Maven coordinates
 * as they are produced locally during compilation.</p>
 */
@Builder(toBuilder = true)
public record LocalJarDependency(
        String path,
        String description,
        Scope scope) implements LocalDependency {
}