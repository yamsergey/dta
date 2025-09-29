package io.yamsergey.adt.tools.android.model.dependency;

import java.util.Collection;

import lombok.Builder;

/**
 * Represents an AAR (Android Archive) dependency from an external repository
 * with complete Maven coordinates and resolved JAR files.
 *
 * <p>AAR dependencies contain multiple components:</p>
 * <ul>
 *   <li>classes.jar - compiled Java/Kotlin classes</li>
 *   <li>res/ - Android resources</li>
 *   <li>AndroidManifest.xml - manifest declarations</li>
 *   <li>Additional JAR files (optional)</li>
 * </ul>
 *
 * <p>The resolvedJars field contains the paths to all extracted JAR files
 * from the AAR, typically including classes.jar and any additional JARs.</p>
 *
 * <p>Examples include:</p>
 * <ul>
 *   <li>AndroidX libraries: androidx.appcompat:appcompat:1.7.0</li>
 *   <li>Material components: com.google.android.material:material:1.12.0</li>
 *   <li>Other Android libraries from Google Maven repository</li>
 * </ul>
 */
@Builder(toBuilder = true)
public record GradleAarDependency(
        String path,
        Collection<String> resolvedJars,
        String groupId,
        String artifactId,
        String version,
        Scope scope) implements ExternalDependency {
}