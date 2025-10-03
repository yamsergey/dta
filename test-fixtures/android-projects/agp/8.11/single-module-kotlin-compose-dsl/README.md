## Description

Sample Android project to test [Kotlin's LSP](https://github.com/Kotlin/kotlin-lsp) `workspace.json` feature to setup Android project.

## How it works

Build the project to fetch all required dependencies: `./gradlew assembleDebug`.

Download `KoltinLspClientTwo-1.0-SNAPSHOT.zip` from releases and unzip. Run binary from `bin` folder with next command:

```
./KoltinLspClientTwo --workspacePath "path to this project on your machine"
```

It will generate `workspace.json` with configured dependencies for the android project.
> You can try to generate for your own project' but keep in mind that the work still in progress and multimodule projects are not supported yet.

In best case scenario the LSP server has to be able to succesfully read the `workspace.json` and sucesfuly setup the project.
