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
./gradlew :adt-cli:installDist
# Executable: adt-cli/build/install/adt-cli/bin/adt-cli

# Run CLI directly
./gradlew :adt-cli:run --args="resolve /path/to/android/project --workspace"
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
- `inspect/` - Device and application inspection
  - `ViewHierarchyDumper` - Dumps UI hierarchy from connected Android devices using ADB
  - `ViewHierarchy` - Data model for captured view hierarchy
- `tools.sugar/` - `Result<T>` type (sealed interface: `Success<T>`, `Failure<T>`)

**Key Design Patterns:**
- **Result Pattern**: All operations return serializable `Result<T>` (Success/Failure) for cross-process communication with Gradle daemon. Both Success and Failure carry optional descriptions for debugging.
- **Sealed Interfaces**: `ResolvedModule` and `Result` use sealed interfaces for compile-time exhaustive pattern matching.
- **Strategy Pattern**: `ModuleResolutionStrategy` allows customizable module resolution logic.

### adt-cli (CLI Interface)
Command-line interface wrapping tools-android library.

**Key Files:**
- `App.java` - Main entry point using picocli
- `resolve/ResolveCommand.java` - Project analysis command
- `workspace/WorkspaceCommand.java` - Workspace generation command
- `drawable/DrawableCommand.java` - Vector drawable to PNG conversion
- `inspect/InspectCommand.java` - Device inspection command (parent)
- `inspect/LayoutCommand.java` - UI layout hierarchy dumping subcommand
- `serialization/jackson/` - Jackson customizations for safe Gradle object serialization (`SafeSerializerModifier`, `ParentIgnoreMixIn`, `ProjectMixIn`, `TaskMixIn`)

**CLI Usage:**
```bash
# Analyze project structure
adt-cli resolve /path/to/project --workspace

# List build variants
adt-cli resolve /path/to/project --variants

# Extract raw Gradle data (use --output, result is large)
adt-cli resolve /path/to/project --raw --output data.json

# Save output to file
adt-cli resolve /path/to/project --workspace --output analysis.json

# Dump UI layout hierarchy as JSON (agent-friendly)
adt-cli inspect layout --format json -o hierarchy.json

# Capture screenshot
adt-cli inspect screenshot -o screenshot.png

# Capture logcat logs
adt-cli inspect logcat --lines 1000 -o logcat.txt

# Capture only errors and warnings
adt-cli inspect logcat --priority W -o errors.txt

# Dump from specific device
adt-cli inspect layout -d emulator-5554 --format json
adt-cli inspect screenshot -d emulator-5554 -o screen.png
adt-cli inspect logcat -d emulator-5554 --lines 500 -o logcat.txt

# Use compressed format (faster, less detail)
adt-cli inspect layout --compressed -o hierarchy.xml

# Print JSON to stdout for piping
adt-cli inspect layout --format json
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
