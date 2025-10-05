# tools-android

Core library for Android project analysis using the Gradle Tooling API.

## Overview

This library provides programmatic access to Android project information, enabling analysis of project structure, build variants, dependencies, and source roots. It integrates with the Gradle Tooling API and Android Gradle Plugin to extract comprehensive project data.

## Features

- **Project Structure Analysis**: Resolve Android project modules and their relationships
- **Build Variant Resolution**: Extract build variants with their configurations
- **Dependency Analysis**: Analyze JAR, AAR, and classpath dependencies
- **Source Root Detection**: Identify source directories and their types
- **Result Pattern**: Type-safe Success/Failure results for error handling

## Installation

### Via JitPack

Add JitPack repository to your build file:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add the dependency:

```gradle
dependencies {
    implementation 'com.github.yamsergey.yamsergey.adt:tools-android:1.0.0'
}
```

**Note:** Replace `1.0.0` with the desired version tag from [releases](https://github.com/yamsergey/yamsergey.adt/releases).

## Architecture

### Core Components

#### Model Layer (`model/`)
- `Project`: Top-level project representation with modules
- `ResolvedModule`: Resolved Android/generic modules with dependencies
- `BuildVariant`: Android build variant information
- `Dependency`: JAR, AAR, and classpath dependency types

#### Resolver Layer (`resolver/`)
- `AndroidProjectResolver`: Main entry point for project resolution
- `RawProjectResolver`: Raw project data extraction
- `BuildVariantsResolver`: Build variant analysis
- Module-specific resolvers for sources and dependencies

#### Gradle Integration (`gradle/`)
- Gradle Tooling API integration
- Fetchers for different project aspects (dependencies, variants, basic info)
- Utility classes for Gradle project manipulation

## Usage

### Basic Project Resolution

```java
AndroidProjectResolver resolver = new AndroidProjectResolver("/path/to/android/project");
Result<Project> result = resolver.resolve();

switch (result) {
    case Success<Project> success -> {
        Project project = success.value();
        // Process project structure
        for (ResolvedModule module : project.modules()) {
            System.out.println("Module: " + module.name());
        }
    }
    case Failure<Project> failure -> {
        System.err.println("Failed to resolve project: " + failure.reason());
    }
}
```

### Build Variant Analysis

```java
AndroidProjectResolver resolver = new AndroidProjectResolver("/path/to/android/project");
Result<Collection<BuildVariant>> variants = resolver.resolveBuildVariants();

switch (variants) {
    case Success<Collection<BuildVariant>> success -> {
        for (BuildVariant variant : success.value()) {
            System.out.println("Variant: " + variant.displayName());
        }
    }
    case Failure<Collection<BuildVariant>> failure -> {
        System.err.println("Failed to resolve variants: " + failure.reason());
    }
}
```

### Raw Project Data

```java
BuildVariant debugVariant = BuildVariant.builder()
    .displayName("debug")
    .name("debug")
    .isDefault(true)
    .build();

RawProjectResolver resolver = new RawProjectResolver(debugVariant, "/path/to/android/project");
RawProject rawProject = resolver.resolve();
```

## Dependencies

The library integrates with:

- **Gradle Tooling API 8.10.2**: Core Gradle project access
- **Android Gradle Plugin 8.7.1**: Android-specific project models
- **Google Guava**: Utility collections and functions
- **Lombok**: Code generation for models
- **GSON**: JSON serialization for debugging

## Error Handling

The library uses a custom Result pattern specifically designed for cross-process communication with Gradle daemon. Since operations execute in separate processes (Gradle daemon), shared logging configuration isn't possible. Instead, every operation returns a serializable Result that can pass execution details between processes.

Key features:
- **Serializable Design**: Enables communication between main process and Gradle daemon
- **Rich Context**: Both Success and Failure carry optional descriptions for debugging and process understanding
- **Error Forwarding**: Failures can be forwarded to different result types when propagating errors
- **Functional Processing**: Built-in mapping for functional-style result handling
- **Type Safety**: Sealed interface ensures compile-time safety with Success/Failure cases
