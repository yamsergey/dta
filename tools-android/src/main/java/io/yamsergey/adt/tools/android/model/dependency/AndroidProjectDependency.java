package io.yamsergey.adt.tools.android.model.dependency;

import java.util.List;

import lombok.Builder;

/**
 * Represents a dependency on an Android Gradle module within the same project.
 *
 * <p>Android project dependencies are variant-specific, meaning they depend on
 * a specific build type (e.g., debug, release) of the target module.</p>
 *
 * <p>Key format example:</p>
 * <pre>
 * :|:kotlin-android-library-one|debug|com.android.build.api.attributes.AgpVersionAttr>8.13.0,
 * com.android.build.gradle.internal.attributes.VariantAttr>debug,
 * org.gradle.category>library,
 * org.gradle.jvm.environment>android,
 * org.gradle.usage>java-api,
 * org.jetbrains.kotlin.platform.type>androidJvm|Multi-Module-Kotlin-Compose:kotlin-android-library-one:unspecified
 * </pre>
 *
 * <p>Key components:</p>
 * <ul>
 *   <li>Build ID: ":" (root build)</li>
 *   <li>Project path: ":kotlin-android-library-one"</li>
 *   <li>Build type: "debug" or "release"</li>
 *   <li>Attributes: Android-specific Gradle attributes</li>
 *   <li>Capabilities: What this component provides. Typically contains a single element with
 *       Maven-style coordinates (e.g., "Multi-Module-Kotlin-Compose:app:unspecified").
 *       Used by Gradle for conflict resolution and feature variants.</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>implementation(project(":app")) - debug variant</li>
 *   <li>implementation(project(":core")) - release variant</li>
 * </ul>
 *
 * @param path Artifact path (typically null for Android project dependencies)
 * @param projectPath Gradle project path (e.g., ":kotlin-android-library-one")
 * @param buildType Android build type (e.g., "debug", "release")
 * @param capabilities Gradle capabilities - what this component provides. Typically contains
 *                     a single element with Maven-style coordinates (e.g., "Multi-Module-Kotlin-Compose:app:unspecified").
 *                     Used by Gradle for conflict resolution and feature variants.
 * @param scope Dependency scope (COMPILE, TEST, RUNTIME)
 */
@Builder(toBuilder = true)
public record AndroidProjectDependency(
        String path,
        String projectPath,
        String buildType,
        List<String> capabilities,
        Scope scope) implements ProjectDependency {
}
