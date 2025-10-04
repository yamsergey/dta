package io.yamsergey.adt.tools.android.resolver;

import java.util.Collection;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;

import io.yamsergey.adt.tools.android.gradle.FetchIdeaProject;
import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.SourceRoot.Language;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.dependency.GradleJarDependency;
import io.yamsergey.adt.tools.android.model.module.FailedModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedGenericModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * Specialized resolver for generic (non-Android) Gradle modules.
 *
 * <p>Generic modules include Java libraries, Kotlin libraries, and other JVM-based
 * modules that don't use the Android Gradle Plugin. This resolver extracts source roots
 * and dependencies from the IDEA project model provided by the Gradle Tooling API.</p>
 *
 * @see ResolvedGenericModule
 */
public class GenericModuleResolver {

    /**
     * Resolves a generic (non-Android) Gradle module with source roots and dependencies.
     *
     * @param subProject the generic Gradle project to resolve
     * @param connection the Gradle project connection
     * @return {@link ResolvedModule} with source roots and dependencies
     */
    public ResolvedModule resolve(
            @Nonnull GradleProject subProject,
            @Nonnull ProjectConnection connection) {

        var ideaProjectResult = connection.action(
                FetchIdeaProject.builder().project(subProject).build()).run();

        return switch (ideaProjectResult) {
            case Success<IdeaProject> success -> {
                var ideaProject = success.value();
                var module = findModule(ideaProject, subProject.getName());

                if (module != null) {
                    var roots = extractSourceRoots(module);
                    var dependencies = extractDependencies(module);

                    yield ResolvedGenericModule.builder()
                            .name(subProject.getName())
                            .path(subProject.getPath())
                            .roots(roots)
                            .dependencies(dependencies)
                            .build();
                } else {
                    yield FailedModule.builder()
                            .name(subProject.getName())
                            .path(subProject.getPath())
                            .details(String.format("Module '%s' not found in IdeaProject", subProject.getName()))
                            .build();
                }
            }
            case Failure<IdeaProject> failure -> FailedModule.from(failure, subProject);
            default -> FailedModule.builder()
                    .name(subProject.getName())
                    .path(subProject.getPath())
                    .details(String.format("Unknown result of IdeaProject resolution for: %s", subProject.getPath()))
                    .build();
        };
    }

    private IdeaModule findModule(IdeaProject ideaProject, String moduleName) {
        for (var module : ideaProject.getModules()) {
            if (module.getName().equals(moduleName)) {
                return module;
            }
        }
        return null;
    }

    private Collection<SourceRoot> extractSourceRoots(IdeaModule module) {
        return module.getContentRoots().stream()
                .flatMap(contentRoot -> Stream.concat(
                        contentRoot.getSourceDirectories().stream()
                                .map(sourceDir -> SourceRoot.builder()
                                        .path(sourceDir.getDirectory().getAbsolutePath())
                                        .language(Language.JAVA)
                                        .build()),
                        contentRoot.getTestDirectories().stream()
                                .map(testDir -> SourceRoot.builder()
                                        .path(testDir.getDirectory().getAbsolutePath())
                                        .language(Language.JAVA)
                                        .build())
                ))
                .toList();
    }

    private Collection<Dependency> extractDependencies(IdeaModule module) {
        return module.getDependencies().stream()
                .filter(dependency -> dependency instanceof IdeaSingleEntryLibraryDependency)
                .map(dependency -> (IdeaSingleEntryLibraryDependency) dependency)
                .filter(libraryDep -> libraryDep.getFile() != null && libraryDep.getGradleModuleVersion() != null)
                .map(libraryDep -> {
                    var file = libraryDep.getFile();
                    var gradleModule = libraryDep.getGradleModuleVersion();

                    return GradleJarDependency.builder()
                            .path(file.getAbsolutePath())
                            .groupId(gradleModule.getGroup())
                            .artifactId(gradleModule.getName())
                            .version(gradleModule.getVersion())
                            .scope(convertScope(libraryDep.getScope()))
                            .build();
                })
                .map(dep -> (Dependency) dep)
                .toList();
    }

    private Dependency.Scope convertScope(org.gradle.tooling.model.idea.IdeaDependencyScope ideaScope) {
        if (ideaScope == null) {
            return Dependency.Scope.COMPILE;
        }

        return switch (ideaScope.getScope()) {
            case "COMPILE" -> Dependency.Scope.COMPILE;
            case "TEST" -> Dependency.Scope.TEST;
            case "RUNTIME" -> Dependency.Scope.RUNTIME;
            default -> Dependency.Scope.COMPILE;
        };
    }
}