plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "io.yamsergey.dta"
version = rootProject.findProperty("dtaVersion") ?: "0.0.0"

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://repo.gradle.org/gradle/libs-releases")
    intellijPlatform {
        defaultRepositories()
    }
}

fun findAndroidStudio(): String {
    // 1. Gradle property: -PandroidStudioPath=/path
    findProperty("androidStudioPath")?.toString()?.let { return it }
    // 2. Environment variable
    System.getenv("ANDROID_STUDIO_PATH")?.let { return it }
    // 3. Common install locations
    listOf(
        "/Applications/Android Studio.app",
        "${System.getProperty("user.home")}/android-studio",
        "C:\\Program Files\\Android\\Android Studio",
    ).forEach { if (file(it).exists()) return it }
    error("Android Studio not found. Set -PandroidStudioPath=/path or ANDROID_STUDIO_PATH env var.")
}

dependencies {
    intellijPlatform {
        local(findAndroidStudio())
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.gradle")
        pluginVerifier()
        instrumentationTools()
    }

    implementation(project(":dta-daemon"))
    // dta-mcp gives us McpHttpServer + McpInstaller for the in-plugin MCP
    // tab. The plugin hosts the same HTTP MCP server the CLI does.
    implementation(project(":dta-mcp"))
    implementation("tools.jackson.core:jackson-databind:3.1.1")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.yamsergey.dta"
        name = "DTA - Development Tools for Android"
        version = project.version.toString()
        description = "AI-powered Android development tools with layout inspection, network capture, and MCP integration"
        vendor {
            name = "Sergey Yamshchikov"
            url = "https://github.com/yamsergey/dta"
        }
        ideaVersion {
            sinceBuild = "243"  // Android Studio Meerkat Feature Drop (2024.3.2)+
            untilBuild = provider { null }  // no upper bound
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks {

    // buildSearchableOptions requires launching the full IDE, which fails with local() installs
    buildSearchableOptions {
        enabled = false
    }
}
