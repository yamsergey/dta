package io.yamsergey.adt.workspace.kotlin.converter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import com.android.tools.r8.internal.Li;

import io.yamsergey.adt.tools.android.model.dependency.AndroidProjectDependency;
import io.yamsergey.adt.tools.android.model.dependency.ClassFolderDependency;
import io.yamsergey.adt.tools.android.model.dependency.ExternalDependency;
import io.yamsergey.adt.tools.android.model.dependency.GenericProjectDependency;
import io.yamsergey.adt.tools.android.model.dependency.GradleAarDependency;
import io.yamsergey.adt.tools.android.model.dependency.GradleJarDependency;
import io.yamsergey.adt.tools.android.model.dependency.LocalDependency;
import io.yamsergey.adt.tools.android.model.dependency.LocalJarDependency;
import io.yamsergey.adt.tools.android.model.dependency.ProjectDependency;
import io.yamsergey.adt.tools.android.model.module.ResolvedAndroidModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedGenericModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.android.model.project.Project;
import io.yamsergey.adt.workspace.kotlin.model.ContentRoot;
import io.yamsergey.adt.workspace.kotlin.model.Dependency;
import io.yamsergey.adt.workspace.kotlin.model.KotlinSettings;
import io.yamsergey.adt.workspace.kotlin.model.Library;
import io.yamsergey.adt.workspace.kotlin.model.Module;
import io.yamsergey.adt.workspace.kotlin.model.Sdk;
import io.yamsergey.adt.workspace.kotlin.model.SourceRoot;
import io.yamsergey.adt.workspace.kotlin.model.Workspace;

/**
 * Converts Android Development Tools Project model to Kotlin LSP workspace.json
 * format.
 *
 * This converter transforms our internal project representation into the format
 * expected by Kotlin LSP for IDE integration and language server functionality.
 */
public class ProjectToWorkspaceConverter {

  /**
   * Converts a Project to Kotlin LSP Workspace format.
   *
   * @param project the Android Development Tools project to convert
   * @return Workspace model ready for JSON serialization
   */
  public Workspace convert(@Nonnull Project project) {
    Collection<Module> modules = convertModules(project.modules());
    Collection<Library> libraries = extractLibraries(project.modules());
    Collection<Sdk> sdks = List.of();
    Collection<KotlinSettings> kotlinSettings = List.of();

    return Workspace.builder()
        .modules(modules)
        .libraries(libraries)
        .sdks(sdks)
        .kotlinSettings(kotlinSettings)
        .build();
  }

  /**
   * Converts project modules to workspace modules.
   * Each Android module gets converted to main and test modules.
   */
  private Collection<Module> convertModules(Collection<ResolvedModule> projectModules) {
    return projectModules.stream()
        .flatMap(module -> convertSingleModule(module).stream())
        .collect(Collectors.toList());
  }

  /**
   * Converts a single project module to one or more workspace modules.
   * Android modules typically generate both main and test modules.
   */
  private Collection<Module> convertSingleModule(ResolvedModule projectModule) {
    List<Module> result = new ArrayList<>();

    // Handle different module types
    switch (projectModule) {
      case ResolvedAndroidModule androidModule -> {
        // Create main module
        result.add(createMainModule(androidModule));

        // Create test module if test sources exist
        if (hasTestSources(androidModule)) {
          result.add(createTestModule(androidModule));
        }
      }
      case ResolvedGenericModule genericModule -> {
        // Create main module
        result.add(createMainModule(genericModule));

        // Create test module if test sources exist
        if (hasTestSources(genericModule)) {
          result.add(createTestModule(genericModule));
        }
      }
      default -> {
        // Skip FailedModule and UnknownModule
      }
    }

    return result;
  }

  /**
   * Creates the main module for an Android module.
   */
  private Module createMainModule(ResolvedAndroidModule projectModule) {
    String moduleName = projectModule.name();
    Collection<Dependency> dependencies = convertDependencies(projectModule, false);
    Collection<ContentRoot> contentRoots = convertContentRoots(projectModule, false);

    return Module.builder()
        .name(moduleName)
        .dependencies(dependencies)
        .contentRoots(contentRoots)
        .facets(List.of())
        .build();
  }

  /**
   * Creates the main module for a generic module.
   */
  private Module createMainModule(ResolvedGenericModule projectModule) {
    String moduleName = projectModule.name() + ".main";
    Collection<Dependency> dependencies = convertDependencies(projectModule, false);
    Collection<ContentRoot> contentRoots = convertContentRoots(projectModule, false);

    return Module.builder()
        .name(moduleName)
        .dependencies(dependencies)
        .contentRoots(contentRoots)
        .facets(List.of())
        .build();
  }

  /**
   * Creates the test module for an Android module.
   */
  private Module createTestModule(ResolvedAndroidModule projectModule) {
    String moduleName = projectModule.name() + ".test";
    Collection<Dependency> dependencies = convertDependencies(projectModule, true);
    Collection<ContentRoot> contentRoots = convertContentRoots(projectModule, true);

    // Add dependency on main module
    Collection<Dependency> allDependencies = new ArrayList<>(dependencies);
    allDependencies.add(Dependency.builder()
        .type(Dependency.DependencyType.module)
        .name(projectModule.name() + ".main")
        .scope(Dependency.DependencyScope.compile)
        .build());

    return Module.builder()
        .name(moduleName)
        .dependencies(allDependencies)
        .contentRoots(contentRoots)
        .facets(List.of())
        .build();
  }

  /**
   * Creates the test module for a generic module.
   */
  private Module createTestModule(ResolvedGenericModule projectModule) {
    String moduleName = projectModule.name() + ".test";
    Collection<Dependency> dependencies = convertDependencies(projectModule, true);
    Collection<ContentRoot> contentRoots = convertContentRoots(projectModule, true);

    // Add dependency on main module
    Collection<Dependency> allDependencies = new ArrayList<>(dependencies);
    allDependencies.add(Dependency.builder()
        .type(Dependency.DependencyType.module)
        .name(projectModule.name() + ".main")
        .scope(Dependency.DependencyScope.compile)
        .build());

    return Module.builder()
        .name(moduleName)
        .dependencies(allDependencies)
        .contentRoots(contentRoots)
        .facets(List.of())
        .build();
  }

  /**
   * Converts project dependencies to workspace dependencies for Android modules.
   */
  private Collection<Dependency> convertDependencies(ResolvedAndroidModule projectModule, boolean testScope) {
    return projectModule.dependencies().stream()
        .filter(dep -> testScope ? isTestDependency(dep) : !isTestDependency(dep))
        .map(dependency -> convertDependency(dependency, projectModule.name()))
        .collect(Collectors.toList());
  }

  /**
   * Converts project dependencies to workspace dependencies for generic modules.
   */
  private Collection<Dependency> convertDependencies(ResolvedGenericModule projectModule, boolean testScope) {
    return projectModule.dependencies().stream()
        .filter(dep -> testScope ? isTestDependency(dep) : !isTestDependency(dep))
        .map(dependnecy -> convertDependency(dependnecy, projectModule.name()))
        .collect(Collectors.toList());
  }

  /**
   * Converts a single project dependency to workspace dependency.
   * Uses the new improved dependency structure with external vs local separation.
   *
   * Local dependencies require module name to create uniqe dependency id, in
   * other case one dependency can override another (e.g. R.jar)
   */
  private Dependency convertDependency(io.yamsergey.adt.tools.android.model.dependency.Dependency projectDep,
      String moduleName) {
    return switch (projectDep) {
      case GradleJarDependency jar -> Dependency.builder()
          .type(Dependency.DependencyType.library)
          .name("Gradle: " + jar.groupId() + ":" + jar.artifactId() + ":" + jar.version())
          .scope(convertScope(jar.scope()))
          .build();
      case GradleAarDependency aar -> Dependency.builder()
          .type(Dependency.DependencyType.library)
          .name("Gradle: " + aar.groupId() + ":" + aar.artifactId() + ":" + aar.version())
          .scope(convertScope(aar.scope()))
          .build();
      case AndroidProjectDependency androidProject -> Dependency.builder()
          .type(Dependency.DependencyType.module)
          .name(androidProject.projectPath().substring(1)) // Remove leading ":"
          .scope(convertScope(androidProject.scope()))
          .build();
      case GenericProjectDependency genericProject -> Dependency.builder()
          .type(Dependency.DependencyType.module)
          .name(genericProject.projectPath().substring(1) + ".main") // Remove leading ":" and add ".main"
          .scope(convertScope(genericProject.scope()))
          .build();
      case LocalJarDependency localJar -> {
        String depName;
        if (localJar.path().contains("android.jar")) {
          String version = extractAndroidApiVersion(localJar.path());
          depName = String.format("Gradle: %s %s", moduleName, "android:android:" + version);
        } else {
          depName = String.format("Gradle: %s %s", moduleName, extractLibraryName(localJar.path()));
        }
        yield Dependency.builder()
            .type(Dependency.DependencyType.library)
            .name(depName)
            .scope(convertScope(localJar.scope()))
            .build();
      }
      case ClassFolderDependency classFolder -> Dependency.builder()
          .type(Dependency.DependencyType.library)
          .name("Gradle: " + extractLibraryName(classFolder.path()))
          .scope(convertScope(classFolder.scope()))
          .build();
    };
  }

  /**
   * Converts project content to workspace content roots for Android modules.
   */
  private Collection<ContentRoot> convertContentRoots(ResolvedAndroidModule projectModule, boolean testScope) {
    // Include all source roots (main + test) in content roots
    // The testScope parameter is only used to filter which roots are returned for main vs test modules
    Collection<SourceRoot> sourceRoots = projectModule.roots().stream()
        .filter(root -> testScope ? isTestSourceRoot(root) : !isTestSourceRoot(root))
        .map(this::convertSourceRoot)
        .collect(Collectors.toList());

    if (sourceRoots.isEmpty()) {
      return List.of();
    }

    return List.of(ContentRoot.builder()
        .path(projectModule.path())
        .sourceRoots(sourceRoots)
        .build());
  }

  /**
   * Converts project content to workspace content roots for generic modules.
   */
  private Collection<ContentRoot> convertContentRoots(ResolvedGenericModule projectModule, boolean testScope) {
    // For now, create a single content root per module
    // In a more sophisticated implementation, we might group by directory structure

    Collection<SourceRoot> sourceRoots = projectModule.roots().stream()
        .filter(root -> testScope ? isTestSourceRoot(root) : !isTestSourceRoot(root))
        .map(this::convertSourceRoot)
        .collect(Collectors.toList());

    if (sourceRoots.isEmpty()) {
      return List.of();
    }

    return List.of(ContentRoot.builder()
        .path(projectModule.path())
        .sourceRoots(sourceRoots)
        .build());
  }

  /**
   * Converts project source root to workspace source root.
   * Determines if it's a test source and uses appropriate type.
   */
  private SourceRoot convertSourceRoot(io.yamsergey.adt.tools.android.model.SourceRoot projectRoot) {
    boolean isTest = isTestSourceRoot(projectRoot);

    SourceRoot.SourceRootType type = switch (projectRoot.language()) {
      case JAVA -> isTest ? SourceRoot.SourceRootType.testJava : SourceRoot.SourceRootType.java;
      case KOTLIN -> isTest ? SourceRoot.SourceRootType.testKotlin : SourceRoot.SourceRootType.kotlin;
      case OTHER -> isTest ? SourceRoot.SourceRootType.testResources : SourceRoot.SourceRootType.resources;
    };

    return SourceRoot.builder()
        .path(projectRoot.path())
        .type(type)
        .build();
  }

  /**
   * Extracts all unique libraries from project modules.
   */
  private Collection<Library> extractLibraries(Collection<ResolvedModule> projectModules) {
    Set<SourceRoot> libraryPaths = new HashSet<>();
    List<Library> libraries = new ArrayList<>();

    for (ResolvedModule module : projectModules) {
      var moduleName = switch (module) {
        case ResolvedAndroidModule androidModule -> androidModule.name();
        case ResolvedGenericModule genericModule -> genericModule.name();
        default -> "";
      };
      Collection<io.yamsergey.adt.tools.android.model.dependency.Dependency> dependencies = switch (module) {
        case ResolvedAndroidModule androidModule -> androidModule.dependencies();
        case ResolvedGenericModule genericModule -> genericModule.dependencies();
        default -> List.of();
      };

      for (var dependency : dependencies) {
        // Skip project dependencies - they are module references, not libraries
        if (dependency instanceof ProjectDependency) {
          continue;
        }

        String path = dependency.path();
        SourceRoot root = SourceRoot.builder().path(path).build();
        if (!libraryPaths.contains(root)) {
          libraryPaths.add(root);
          libraries.add(createLibrary(dependency, moduleName));
        }
      }
    }

    return libraries;
  }

  /**
   * Creates a library definition from a project dependency.
   * For AAR dependencies, uses resolved JARs as roots instead of the AAR file.
   * External dependencies get proper Maven coordinates from the dependency
   * itself.
   */
  private Library createLibrary(io.yamsergey.adt.tools.android.model.dependency.Dependency dependency,
      String moduleName) {
    String name;
    Library.LibraryType type = determineLibraryType(dependency);
    Collection<SourceRoot> roots;
    Library.LibraryAttributes attributes;

    switch (dependency) {
      case GradleJarDependency jar -> {
        name = "Gradle: " + jar.groupId() + ":" + jar.artifactId() + ":" + jar.version();
        roots = List.of(SourceRoot.builder().path(jar.path()).build());
        attributes = Library.LibraryAttributes.builder()
            .groupId(jar.groupId())
            .artifactId(jar.artifactId())
            .version(jar.version())
            .baseVersion(jar.version())
            .build();
      }
      case GradleAarDependency aar -> {
        name = "Gradle: " + aar.groupId() + ":" + aar.artifactId() + ":" + aar.version();
        roots = aar.resolvedJars().isEmpty()
            ? List.of(SourceRoot.builder().path(aar.path()).build()) // Fallback to AAR path if no resolved JARs
            : aar.resolvedJars().stream().map(entry -> SourceRoot.builder().path(entry).build()).toList();
        attributes = Library.LibraryAttributes.builder()
            .groupId(aar.groupId())
            .artifactId(aar.artifactId())
            .version(aar.version())
            .baseVersion(aar.version())
            .build();
      }
      case AndroidProjectDependency androidProject -> {
        // Should never reach here - filtered out before calling this method
        throw new IllegalStateException("ProjectDependency should not be converted to Library");
      }
      case GenericProjectDependency genericProject -> {
        // Should never reach here - filtered out before calling this method
        throw new IllegalStateException("ProjectDependency should not be converted to Library");
      }
      case LocalJarDependency localJar -> {
        // Special case for android.jar
        if (localJar.path().contains("android.jar")) {
          String version = extractAndroidApiVersion(localJar.path());
          name = String.format("Gradle: %s %s", moduleName, "android:android:" + version);
          attributes = Library.LibraryAttributes.builder()
              .groupId("android")
              .artifactId("android")
              .version(version)
              .baseVersion(version)
              .build();
        } else {
          String libraryName = extractLibraryName(localJar.path());
          name = String.format("Gradle: %s %s", moduleName, libraryName);
          attributes = Library.LibraryAttributes.builder()
              .groupId("local")
              .artifactId(libraryName)
              .version("unknown")
              .baseVersion("unknown")
              .build();
        }
        roots = List.of(SourceRoot.builder().path(localJar.path()).build());
      }
      case ClassFolderDependency classFolder -> {
        name = "Gradle: " + extractLibraryName(classFolder.path());
        roots = List.of(SourceRoot.builder().path(classFolder.path()).build());
        attributes = Library.LibraryAttributes.builder()
            .groupId("local")
            .artifactId(extractLibraryName(classFolder.path()))
            .version("unknown")
            .baseVersion("unknown")
            .build();
      }
    }

    return Library.builder()
        .name(name)
        .type(type)
        .roots(roots)
        .properties(Library.Properties.builder().attributes(attributes).build())
        .build();
  }

  /**
   * Generates Kotlin settings for modules that contain Kotlin code.
   */
  private Collection<KotlinSettings> generateKotlinSettings(Collection<ResolvedModule> projectModules) {
    return projectModules.stream()
        .filter(this::hasKotlinSources)
        .flatMap(module -> generateKotlinSettingsForModule(module).stream())
        .collect(Collectors.toList());
  }

  /**
   * Generates Kotlin settings for a single module.
   */
  private Collection<KotlinSettings> generateKotlinSettingsForModule(ResolvedModule module) {
    List<KotlinSettings> settings = new ArrayList<>();

    // Main module Kotlin settings
    settings.add(KotlinSettings.builder().build());

    return settings;
  }

  // Helper methods

  private boolean hasTestSources(ResolvedModule module) {
    Collection<io.yamsergey.adt.tools.android.model.SourceRoot> roots = switch (module) {
      case ResolvedAndroidModule androidModule -> androidModule.roots();
      case ResolvedGenericModule genericModule -> genericModule.roots();
      default -> List.of();
    };
    return roots.stream().anyMatch(this::isTestSourceRoot);
  }

  private boolean hasKotlinSources(ResolvedModule module) {
    Collection<io.yamsergey.adt.tools.android.model.SourceRoot> roots = switch (module) {
      case ResolvedAndroidModule androidModule -> androidModule.roots();
      case ResolvedGenericModule genericModule -> genericModule.roots();
      default -> List.of();
    };
    return roots.stream()
        .anyMatch(root -> root.language() == io.yamsergey.adt.tools.android.model.SourceRoot.Language.KOTLIN);
  }

  private boolean isTestDependency(io.yamsergey.adt.tools.android.model.dependency.Dependency dependency) {
    return dependency.scope() == io.yamsergey.adt.tools.android.model.dependency.Dependency.Scope.TEST;
  }

  private boolean isTestSourceRoot(io.yamsergey.adt.tools.android.model.SourceRoot root) {
    return root.path().contains("/test/") || root.path().contains("\\test\\");
  }

  private Dependency.DependencyScope convertScope(
      io.yamsergey.adt.tools.android.model.dependency.Dependency.Scope scope) {
    return switch (scope) {
      case COMPILE -> Dependency.DependencyScope.compile;
      case RUNTIME -> Dependency.DependencyScope.runtime;
      case TEST -> Dependency.DependencyScope.test;
    };
  }

  private String extractLibraryName(String path) {
    // Extract library name from path - could be enhanced for better naming
    String fileName = path.substring(path.lastIndexOf('/') + 1);
    return fileName.endsWith(".jar") || fileName.endsWith(".aar")
        ? fileName.substring(0, fileName.lastIndexOf('.'))
        : fileName;
  }

  private String extractAndroidApiVersion(String path) {
    // Extract API version from android.jar path like
    // "/Android/sdk/platforms/android-35/android.jar"
    if (path.contains("android-")) {
      int start = path.indexOf("android-") + 8; // Skip "android-"
      int end = path.indexOf("/", start);
      if (end == -1)
        end = path.length();
      return path.substring(start, end);
    }
    return "unknown";
  }

  private Library.LibraryType determineLibraryType(
      io.yamsergey.adt.tools.android.model.dependency.Dependency dependency) {
    return switch (dependency) {
      case GradleJarDependency jar -> Library.LibraryType.jar;
      case GradleAarDependency aar -> Library.LibraryType.jar; // AAR resolves to JAR files
      case AndroidProjectDependency androidProject -> throw new IllegalStateException("ProjectDependency should not be converted to Library");
      case GenericProjectDependency genericProject -> throw new IllegalStateException("ProjectDependency should not be converted to Library");
      case LocalJarDependency localJar -> Library.LibraryType.jar;
      case ClassFolderDependency classFolder -> Library.LibraryType.directory;
    };
  }

  private Library.LibraryAttributes extractLibraryAttributes(String path) {
    // This could be enhanced to parse Maven coordinates from the path
    // For now, return basic attributes
    return Library.LibraryAttributes.builder()
        .groupId("unknown")
        .artifactId(extractLibraryName(path))
        .version("unknown")
        .baseVersion("unknown")
        .build();
  }
}
