package io.yamsergey.adt.tools.android.model.dependency;

import lombok.Builder;

/**
 * Represents a dependency on a directory containing compiled .class files.
 *
 * <p>Class folder dependencies are typically compilation output directories
 * that contain .class files needed for the classpath during compilation and runtime.
 * These are distinct from JAR dependencies as they point to directories rather
 * than packaged archives.</p>
 *
 * <p>Common examples include:</p>
 * <ul>
 *   <li>Java compilation output: {@code build/intermediates/javac/debug/classes}</li>
 *   <li>Kotlin compilation output: {@code build/tmp/kotlin-classes/debug}</li>
 *   <li>Test compilation output: {@code build/intermediates/javac/debugUnitTest/classes}</li>
 * </ul>
 */
@Builder(toBuilder = true)
public record ClassFolderDependency(
        String path,
        String description,
        Scope scope) implements LocalDependency {
}