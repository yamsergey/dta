# workspace-kotlin-cli

Command-line tool for generating workspace.json files for Kotlin Language Server integration with Android projects.

## Overview

This CLI generates workspace.json files that contain structured information about your Android project (modules, dependencies, source roots) for the Kotlin Language Server to provide accurate code completion, navigation, and diagnostics.

## Installation

### From Release

Download from [GitHub Releases](https://github.com/yamsergey/yamsergey.adt/releases):

```bash
# Extract the archive
tar -xzf workspace-kotlin-cli-vX.X.X.tar.gz

# Or for Windows
unzip workspace-kotlin-cli-vX.X.X.zip
```

### Build from Source

```bash
./gradlew :workspace-kotlin-cli:installDist

# Executable will be at:
# workspace-kotlin-cli/build/install/workspace-kotlin-cli/bin/workspace-kotlin-cli
```

## Usage

### Generate workspace.json

```bash
workspace-kotlin-cli generate /path/to/android/project \
  --output /path/to/android/project/workspace.json
```

**Arguments:**
- `PROJECT_PATH`: Path to Android project directory (default: current directory)

**Options:**
- `--output, -o FILE`: Output file path (default: `workspace.json` in project root)
- `--build-variant VARIANT`: Build variant to use (default: `debug`)
- `--help, -h`: Show help message
- `--version, -V`: Show version information

### Examples

```bash
# Generate workspace.json in project directory
workspace-kotlin-cli generate /path/to/android/project

# Generate workspace.json with custom output path
workspace-kotlin-cli generate /path/to/android/project \
  --output /custom/path/workspace.json

# Generate for specific build variant
workspace-kotlin-cli generate /path/to/android/project \
  --build-variant release

# Generate for current directory
workspace-kotlin-cli generate . --output workspace.json
```

## Output Format

The generated workspace.json contains:

- **modules**: List of all modules with their dependencies and source roots
- **libraries**: External dependencies (JARs, AARs) with their file paths
- **sdks**: SDK information for the project
- **kotlinSettings**: Kotlin compiler settings per module

Example structure:
```json
{
  "modules": [
    {
      "name": "app",
      "dependencies": [...],
      "contentRoots": [
        {
          "path": "/path/to/module",
          "sourceRoots": [
            {
              "path": "/path/to/module/src/main/java",
              "type": "java-source"
            }
          ]
        }
      ],
      "facets": []
    }
  ],
  "libraries": [...],
  "sdks": [],
  "kotlinSettings": []
}
```

## Requirements

- Java 21 or higher
- Target project must use Gradle 8.0+ and Android Gradle Plugin 8.0+

## Troubleshooting

**Issue: Command not found**
- Ensure the bin directory is in your PATH or use the full path to the executable

**Issue: Failed to resolve project**
- Verify the project path is correct
- Ensure the project has a valid build.gradle/build.gradle.kts
- Check that Gradle can build the project successfully

**Issue: Missing dependencies in output**
- Try syncing the project with Gradle first: `./gradlew build`
- Verify the build variant exists in your project

## Related

- [workspace-kotlin](../workspace-kotlin/README.md) - Underlying converter library
- [tools-android-cli](../tools-android-cli/README.md) - General project analysis tool
- [Kotlin Language Server](https://github.com/fwcd/kotlin-language-server) - LSP implementation
