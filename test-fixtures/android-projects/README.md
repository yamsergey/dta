# Test Fixtures - Android Projects

This directory contains real Android projects used for integration testing of the Android Development Tools (ADT) library.

## Directory Structure

```
android-projects/
└── agp/
    ├── 8.10/
    ├── 8.11/
    └── 8.13/
        ├── single-module-kotlin-compose-dsl/
        ├── multi-module-kotlin-compose-dsl/
        └── ...
```

Projects are organized by **Android Gradle Plugin (AGP) version** to enable testing across different AGP releases.

## Current Test Projects

### AGP 8.13

#### `single-module-kotlin-compose-dsl/`
- **Type**: Single module Android application
- **Language**: Kotlin
- **Build Script**: Kotlin DSL (`*.gradle.kts`)
- **Features**:
  - Jetpack Compose
  - AndroidX dependencies
  - Version catalog (`libs.versions.toml`)
- **AGP Version**: 8.13.0
- **Kotlin Version**: 2.0.21

#### `multi-module-kotlin-compose-dsl/`
- **Type**: Multi-module Android project
- **Language**: Kotlin
- **Build Script**: Kotlin DSL (`*.gradle.kts`)
- **Modules**:
  - `:app` - Android application module
  - `:kotlin-android-library-one` - Android library module
  - `:kotlin-generic-library-one` - Generic Kotlin library module
- **Features**:
  - Jetpack Compose
  - Inter-module dependencies
  - Version catalog
- **AGP Version**: 8.13.0
- **Kotlin Version**: 2.0.21

### AGP 8.11

#### `single-module-kotlin-compose-dsl/`
- **Type**: Single module Android application
- **Language**: Kotlin
- **Build Script**: Kotlin DSL (`*.gradle.kts`)
- **Features**: Jetpack Compose, AndroidX
- **AGP Version**: 8.10.0
- **Kotlin Version**: 2.0.21

## Using Test Projects in Tests

Test projects are located at the repository root, outside the main Gradle multi-project build. Access them from your tests using relative paths:

```java
import java.nio.file.Path;
import java.nio.file.Paths;

public class MyIntegrationTest {
    private static final Path TEST_FIXTURES =
        Paths.get("../../test-fixtures/android-projects");

    @Test
    public void testSingleModuleProject() {
        Path projectPath = TEST_FIXTURES
            .resolve("agp/8.13/single-module-kotlin-compose-dsl");

        // Use with AndroidProjectResolver
        var result = AndroidProjectResolver.resolve(projectPath.toFile());
        // ...
    }
}
```

## Adding New Test Projects

### Naming Convention

Follow this pattern: `<complexity>-<language>-<features>-<dsl>`

Examples:
- `single-module-kotlin-compose-dsl`
- `multi-module-java-groovy`
- `flavored-app-kotlin-dsl`
- `library-only-kotlin-dsl`

### Project Requirements

Each test project **must** include:

1. **Complete Gradle wrapper** - `gradlew`, `gradlew.bat`, `gradle/wrapper/`
2. **Build files** - `build.gradle.kts` (or `.gradle`), `settings.gradle.kts`
3. **Gradle properties** - `gradle.properties` with basic configuration
4. **Version catalog** (recommended) - `gradle/libs.versions.toml`
5. **Minimal source code** - At least one source file to ensure valid Android project
6. **`.gitignore`** - Exclude build outputs, IDE files, local.properties

### AGP Version Organization

Place projects under the appropriate AGP version directory:
- `agp/8.10/` - AGP 8.10.x
- `agp/8.11/` - AGP 8.11.x
- `agp/8.13/` - AGP 8.13.x
- etc.

Create new version directories as needed for testing different AGP releases.

### Steps to Add a New Test Project

1. Create project directory under appropriate AGP version:
   ```bash
   mkdir -p test-fixtures/android-projects/agp/8.13/my-new-test-project
   ```

2. Generate project using Android Studio or copy from existing template

3. Verify project builds successfully:
   ```bash
   cd test-fixtures/android-projects/agp/8.13/my-new-test-project
   ./gradlew build
   ```

4. Add `.gitignore` to exclude build artifacts:
   ```gitignore
   *.iml
   .gradle
   /local.properties
   /.idea/
   .DS_Store
   /build
   /captures
   .externalNativeBuild
   .cxx
   ```

5. Commit the project (source only, no build outputs)

6. Update this README with project details

## Project Types Roadmap

Consider adding these project types for comprehensive testing:

- [ ] **Flavored apps** - Product flavors and build types
- [ ] **Library-only projects** - Pure Android library modules
- [ ] **Java projects** - Java-only Android apps
- [ ] **Groovy DSL projects** - Using `build.gradle` instead of `.kts`
- [ ] **Native projects** - JNI/NDK integration
- [ ] **Mixed language** - Java + Kotlin
- [ ] **Different AGP versions** - 7.x, 8.0-8.9, latest
- [ ] **Legacy projects** - Older configurations for backward compatibility testing

## Build Output Exclusions

Test project build outputs are automatically excluded via root `.gitignore`:
- `build/` directories
- `.gradle/` directories
- `local.properties`
- `.idea/` directories

Each test project also includes its own `.gitignore` for additional exclusions.

## Testing Guidelines

- **Keep projects minimal** - Only include dependencies/features needed for test scenarios
- **Use version catalogs** - Centralize dependency versions in `libs.versions.toml`
- **Document purpose** - Update this README when adding new test projects
- **Verify builds** - Ensure test projects build successfully before committing
- **Test isolation** - Each project should be self-contained and buildable independently
