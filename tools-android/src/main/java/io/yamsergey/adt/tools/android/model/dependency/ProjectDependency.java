package io.yamsergey.adt.tools.android.model.dependency;

/**
 * Represents a dependency on another Gradle module within the same project.
 *
 * <p>Project dependencies are declared using the project() function in Gradle:</p>
 * <pre>
 * implementation(project(":core"))
 * implementation(project(":feature:auth"))
 * </pre>
 *
 * <p>Two types of project dependencies exist:</p>
 * <ul>
 *   <li>{@link AndroidProjectDependency} - Android modules with variant-specific dependencies</li>
 *   <li>{@link GenericProjectDependency} - Generic JVM/Kotlin modules</li>
 * </ul>
 *
 * <p>Key format patterns:</p>
 * <ul>
 *   <li>Android: ":|:module-name|buildType|android-attributes|coordinates"</li>
 *   <li>Generic: ":|:module-name|jvm-attributes|coordinates"</li>
 * </ul>
 */
public sealed interface ProjectDependency extends Dependency
    permits AndroidProjectDependency, GenericProjectDependency {

    /**
     * The Gradle project path (e.g., ":kotlin-android-library-one", ":core")
     */
    String projectPath();
}
