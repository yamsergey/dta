package io.yamsergey.adt.tools.android.resolver;

import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.isAndroidProject;

import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;

/**
 * Default implementation of {@link ModuleResolutionStrategy} that orchestrates comprehensive
 * module resolution with Gradle project tree flattening and specialized module resolvers.
 *
 * <p>This resolver acts as a coordinator that:</p>
 *
 * <ul>
 *   <li><strong>Flattens Gradle tree structure</strong> - Recursively processes all subprojects
 *       in a multi-module Gradle build, regardless of nesting depth</li>
 *   <li><strong>Delegates to specialized resolvers</strong> - Uses {@link AndroidModuleResolver}
 *       for Android modules and {@link GenericModuleResolver} for generic modules</li>
 *   <li><strong>Module type detection</strong> - Uses {@link GradleProjectUtils#isAndroidProject(GradleProject)}
 *       to determine the appropriate resolution strategy</li>
 *   <li><strong>Comprehensive orchestration</strong> - Coordinates the entire resolution process
 *       while delegating specific logic to specialized components</li>
 * </ul>
 *
 * <h3>Resolution Architecture</h3>
 * <p>The resolution process follows a clear separation of concerns:</p>
 * <ol>
 *   <li><strong>Tree Flattening</strong> - This resolver handles Gradle project tree traversal</li>
 *   <li><strong>Type Detection</strong> - Determines Android vs generic module type</li>
 *   <li><strong>Specialized Resolution</strong> - Delegates to {@link AndroidModuleResolver} or {@link GenericModuleResolver}</li>
 *   <li><strong>Result Aggregation</strong> - Collects and returns all resolved modules</li>
 * </ol>
 *
 * <h3>Dependency Processing</h3>
 * <p>Dependency resolution is handled by specialized resolvers:</p>
 * <ul>
 *   <li><strong>Android modules</strong> - {@link AndroidModuleResolver} coordinates with
 *       {@link AndroidModuleDependencyResolver} for complete dependency tree resolution</li>
 *   <li><strong>Generic modules</strong> - {@link GenericModuleResolver} provides basic
 *       module information (extensible for future dependency resolution)</li>
 * </ul>
 *
 * @see ModuleResolutionStrategy
 * @see AndroidModuleResolver
 * @see GenericModuleResolver
 * @see GradleProjectUtils#isAndroidProject(GradleProject)
 */
public class DefaultModuleResolver implements ModuleResolutionStrategy {

    /**
     * Creates a new DefaultModuleResolver with specialized resolvers for different module types.
     */
    public DefaultModuleResolver() {
    }

    /**
     * Resolves all modules in a Gradle project by flattening the project tree structure
     * and delegating to specialized resolvers based on module type.
     *
     * @param connection established connection to the Gradle project
     * @param rootProject the root Gradle project to process
     * @param selectedBuildVariant the build variant to use for Android module resolution
     * @return list of resolved modules with full dependency information
     */
    @Override
    public List<ResolvedModule> resolveModules(
            @Nonnull ProjectConnection connection,
            @Nonnull GradleProject rootProject,
            @Nonnull BuildVariant selectedBuildVariant) {
        return flattenGradleProjectTree(rootProject)
                .stream()
                .map(subProject -> resolveModule(subProject, connection, rootProject, selectedBuildVariant))
                .collect(Collectors.toList());
    }

    /**
     * Recursively flattens the Gradle project tree structure to extract all subprojects
     * regardless of nesting depth. This is essential because Gradle projects can have
     * deeply nested module hierarchies that need to be processed uniformly.
     *
     * <p>The flattening ensures that all modules (app, library, feature modules, etc.)
     * are discovered and processed, even in complex multi-module Android applications
     * with nested module structures.</p>
     *
     * @param project the Gradle project to flatten
     * @return flat list of all subprojects found in the tree
     */
    private List<GradleProject> flattenGradleProjectTree(GradleProject project) {
        return project.getChildren()
                .stream()
                .flatMap(child -> {
                    List<GradleProject> childModules = flattenGradleProjectTree(child);
                    childModules.add(0, child);
                    return childModules.stream();
                })
                .collect(Collectors.toList());
    }

    /**
     * Resolves a single Gradle module by determining its type and delegating to
     * the appropriate specialized resolver.
     *
     * @param subProject the Gradle project to resolve
     * @param connection the Gradle project connection
     * @param rootProject the root project for context
     * @param selectedBuildVariant the build variant for Android modules
     * @return resolved module information or failure details
     */
    private ResolvedModule resolveModule(
            GradleProject subProject,
            ProjectConnection connection,
            GradleProject rootProject,
            BuildVariant selectedBuildVariant) {
        if (isAndroidProject(subProject)) {
            return new AndroidModuleResolver().resolve(subProject, connection, rootProject, selectedBuildVariant);
        } else {
            return new GenericModuleResolver().resolve(subProject, connection);
        }
    }


}