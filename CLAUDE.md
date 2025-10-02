# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android Development Tools (ADT) - A multi-module Gradle project providing programmatic access to Android project structure and dependencies using the Gradle Tooling API. Targets Java 21 and uses Gradle 9.0.

## Build Commands

```bash
# Build entire project
./gradlew build

# Build specific module
./gradlew :tools-android:build
./gradlew :tools-android-cli:build

# Run tests
./gradlew test

# Run tests for specific module
./gradlew :tools-android:test

# Clean build artifacts
./gradlew clean

# Clean LSP artifacts (.settings, .project files from jdtls)
./gradlew cleanLsp

# Install CLI distribution
./gradlew :tools-android-cli:installDist
# Executable: tools-android-cli/build/install/tools-android-cli/bin/tools-android-cli

# Run CLI directly
./gradlew :tools-android-cli:run --args="resolve /path/to/android/project --workspace"
```

## Module Architecture

### tools-android (Core Library)
Core library providing Android project analysis via Gradle Tooling API.

**Package Structure:**
- `model/` - Data models for projects, modules, dependencies, variants, and source roots
  - `project/` - `Project`, `RawProject`
  - `module/` - `ResolvedModule` (sealed interface), `ResolvedAndroidModule`, `ResolvedGenericModule`, `FailedModule`, `UnknownModule`, `RawModule`, `RawAndroidModule`, `RawGenericModule`
  - `dependency/` - `Dependency` hierarchy: `ExternalDependency`, `LocalDependency`, `GradleJarDependency`, `GradleAarDependency`, `LocalJarDependency`, `ClassFolderDependency`
  - `variant/` - `BuildVariant`, `ResolvedVariant`
- `resolver/` - Resolution strategies and main entry points
  - `AndroidProjectResolver` - Primary API for project analysis
  - `RawProjectResolver` - Extract raw Gradle project data
  - `BuildVariantsResolver` - Build variant analysis
  - Module resolvers: `AndroidModuleResolver`, `GenericModuleResolver`, `DefaultModuleResolver`
  - Dependency/source resolvers: `AndroidModuleDependencyResolver`, `AndroidModuleSourcesResolver`
  - `ModuleResolutionStrategy` - Strategy pattern for module resolution
- `gradle/` - Gradle Tooling API integration
  - Fetchers: `FetchAndroidProject`, `FetchBasicAndroidProject`, `FetchGradleProject`, `FetchIdeaProject`, `FetchAndroidDependencies`, `FetchAndroidDsl`
  - Utils: `GradleProjectUtils`, `VariantUtils`, `GraphItemUtils`
- `tools.sugar/` - `Result<T>` type (sealed interface: `Success<T>`, `Failure<T>`)

**Key Design Patterns:**
- **Result Pattern**: All operations return serializable `Result<T>` (Success/Failure) for cross-process communication with Gradle daemon. Both Success and Failure carry optional descriptions for debugging.
- **Sealed Interfaces**: `ResolvedModule` and `Result` use sealed interfaces for compile-time exhaustive pattern matching.
- **Strategy Pattern**: `ModuleResolutionStrategy` allows customizable module resolution logic.

### tools-android-cli (CLI Interface)
Command-line interface wrapping tools-android library.

**Key Files:**
- `App.java` - Main entry point using picocli
- `ResolveCommand.java` - Primary command implementation
- `serialization/jackson/` - Jackson customizations for safe Gradle object serialization (`SafeSerializerModifier`, `ParentIgnoreMixIn`, `ProjectMixIn`, `TaskMixIn`)

**CLI Usage:**
```bash
# Analyze project structure
tools-android-cli resolve /path/to/project --workspace

# List build variants
tools-android-cli resolve /path/to/project --variants

# Extract raw Gradle data (use --output, result is large)
tools-android-cli resolve /path/to/project --raw --output data.json

# Save output to file
tools-android-cli resolve /path/to/project --workspace --output analysis.json
```

### workspace-kotlin (Workspace Library)
Converts tools-android `Project` model to workspace format for IDE integration.

**Key Components:**
- `model/` - `Workspace`, `Module`, `Dependency`, `Sdk`, `ContentRoot`, `SourceRoot`, `Library`, `KotlinSettings`
- `converter/ProjectToWorkspaceConverter` - Converts `Project` to `Workspace`
- `serializer/WorkspaceJsonSerializer` - JSON serialization

### workspace-kotlin-cli (Workspace CLI)
CLI for generating workspace files from Android projects.

**Key Files:**
- `App.java` - Main entry point
- `GenerateCommand.java` - Workspace generation command

## Dependencies and Technologies

- **Gradle Tooling API 8.10.2** - Core Gradle project access
- **Android Gradle Plugin 8.7.1** - Android-specific models
- **Google Guava** - Utility collections
- **Lombok** - Code generation for models (builders, getters)
- **Jackson** - JSON serialization (CLI modules)
- **Picocli** - CLI framework
- **JUnit Jupiter** - Testing framework

## Important Architecture Notes

### Cross-Process Communication
The library executes code in the Gradle daemon process (separate from main process), so shared logging isn't possible. This is why the `Result<T>` pattern exists - to pass execution details across process boundaries in a serializable format.

### Module Resolution
Modules are resolved through a strategy pattern:
1. `AndroidProjectResolver.resolve()` fetches all Gradle modules
2. For each module, a `ModuleResolutionStrategy` determines how to resolve it
3. Strategies can produce `ResolvedAndroidModule`, `ResolvedGenericModule`, `UnknownModule`, or `FailedModule`
4. Android modules use `AndroidModuleResolver` which fetches dependencies and sources

### Build Variant Handling
Android modules require a build variant context for dependency resolution. The default is "debug". Raw project resolution requires explicitly specifying a `BuildVariant`.

## Development Notes

- Java 21 is required (sourceCompatibility and targetCompatibility set to VERSION_21)
- All modules apply the java plugin in root build.gradle
- JUnit Platform is used for testing (useJUnitPlatform() configured)
- Version is 1.0.0, group is io.yamsergey.adt
