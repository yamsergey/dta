# workspace-kotlin

Library for converting Android project data to workspace.json format for Kotlin Language Server integration.

## Overview

This library provides the conversion layer between the tools-android project analysis and the workspace.json format expected by the Kotlin Language Server. It transforms detailed project information into a structured format that LSP clients can consume.

## Features

- **Project to Workspace Conversion**: Convert `Project` model to `Workspace` format
- **Module Mapping**: Transform Android and generic modules to workspace modules
- **Dependency Resolution**: Map project dependencies to workspace library references
- **Source Root Detection**: Identify and categorize source roots (java-source, java-test, etc.)
- **JSON Serialization**: Serialize workspace to JSON format

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
    implementation 'com.github.yamsergey.yamsergey.adt:workspace-kotlin:1.0.0'
}
```

**Note:** Replace `1.0.0` with the desired version tag from [releases](https://github.com/yamsergey/yamsergey.adt/releases).

## Architecture

### Model Layer (`model/`)

- `Workspace`: Top-level workspace representation
- `Module`: Module information with dependencies and content roots
- `Library`: External library/dependency definition
- `Dependency`: Module or library dependency reference
- `ContentRoot`: Content root with source roots
- `SourceRoot`: Individual source directory with type
- `Sdk`: SDK information
- `KotlinSettings`: Kotlin-specific settings per module

### Converter Layer (`converter/`)

- `ProjectToWorkspaceConverter`: Main conversion entry point

### Serializer Layer (`serializer/`)

- `WorkspaceJsonSerializer`: JSON serialization for workspace format

## Usage

### Basic Conversion

```java
import io.yamsergey.adt.tools.android.resolver.AndroidProjectResolver;
import io.yamsergey.adt.workspace.kotlin.converter.ProjectToWorkspaceConverter;
import io.yamsergey.adt.workspace.kotlin.model.Workspace;

// Resolve Android project
AndroidProjectResolver resolver = new AndroidProjectResolver("/path/to/project");
Result<Project> projectResult = resolver.resolve();

// Convert to workspace
switch (projectResult) {
    case Success<Project> success -> {
        ProjectToWorkspaceConverter converter = new ProjectToWorkspaceConverter();
        Workspace workspace = converter.convert(success.value());

        // Use workspace...
        System.out.println("Modules: " + workspace.modules().size());
    }
    case Failure<Project> failure -> {
        System.err.println("Failed: " + failure.description());
    }
}
```

### JSON Serialization

```java
import io.yamsergey.adt.workspace.kotlin.serializer.WorkspaceJsonSerializer;

Workspace workspace = converter.convert(project);
WorkspaceJsonSerializer serializer = new WorkspaceJsonSerializer();

// Serialize to JSON string
String json = serializer.serialize(workspace);

// Write to file
serializer.serializeToFile(workspace, new File("workspace.json"));
```

### Module Types

The library handles different module types:

**Android Modules:**
- Main application modules
- Android library modules
- Both converted with full dependency and source information

**Generic Modules:**
- JVM/Kotlin modules (non-Android)
- Separated into `.main` and `.test` modules
- Source roots from IdeaProject model

**Failed Modules:**
- Modules that couldn't be resolved
- Included with error information for debugging

## Workspace Format

The generated workspace follows the structure expected by Kotlin LSP:

```json
{
  "modules": [
    {
      "name": "module-name",
      "dependencies": [
        {
          "type": "library",
          "name": "Gradle: org.jetbrains.kotlin:kotlin-stdlib:1.9.0",
          "scope": "compile"
        },
        {
          "type": "module",
          "name": "other-module",
          "scope": "compile"
        }
      ],
      "contentRoots": [
        {
          "path": "/absolute/path/to/module",
          "excludedPatterns": [],
          "excludedUrls": [],
          "sourceRoots": [
            {
              "path": "/absolute/path/to/module/src/main/java",
              "type": "java-source"
            },
            {
              "path": "/absolute/path/to/module/src/test/java",
              "type": "java-test"
            }
          ]
        }
      ],
      "facets": []
    }
  ],
  "libraries": [
    {
      "name": "Gradle: org.jetbrains.kotlin:kotlin-stdlib:1.9.0",
      "type": "java-imported",
      "roots": [
        {
          "path": "/path/to/kotlin-stdlib-1.9.0.jar"
        }
      ],
      "properties": {
        "attributes": {
          "groupId": "org.jetbrains.kotlin",
          "artifactId": "kotlin-stdlib",
          "version": "1.9.0"
        }
      }
    }
  ],
  "sdks": [],
  "kotlinSettings": []
}
```

## Source Root Types

- `java-source`: Production source code
- `java-test`: Test source code
- `java-resource`: Resource files
- `java-test-resource`: Test resource files

## Dependencies

- **tools-android**: Core Android project analysis
- **Jackson**: JSON serialization
- **Lombok**: Code generation for models

## Related

- [workspace-kotlin-cli](../workspace-kotlin-cli/README.md) - CLI tool using this library
- [tools-android](../tools-android/README.md) - Source project analysis library
