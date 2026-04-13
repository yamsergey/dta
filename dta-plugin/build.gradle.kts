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

fun findLocalAndroidStudio(): String? {
    findProperty("androidStudioPath")?.toString()?.let { return it }
    System.getenv("ANDROID_STUDIO_PATH")?.let { return it }
    listOf(
        "/Applications/Android Studio.app",
        "${System.getProperty("user.home")}/android-studio",
        "C:\\Program Files\\Android\\Android Studio",
    ).forEach { if (file(it).exists()) return it }
    return null
}

// For CI: downloaded AS SDK version (only used when local AS is not found).
// Must be a published stable version from JetBrains repos.
// See: https://plugins.jetbrains.com/docs/intellij/android-studio-releases-list.html
val androidStudioVersion = "2025.2.2.7"

dependencies {
    intellijPlatform {
        val localAs = findLocalAndroidStudio()
        if (localAs != null) {
            local(localAs)
        } else {
            // CI path: downloads the AS SDK for compilation (not the full IDE).
            // Works on any OS — no macOS runner needed.
            androidStudio(androidStudioVersion)
        }
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.gradle")
        pluginVerifier()
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
            sinceBuild = "252"  // Android Studio Otter 3 Feature Drop (2025.2.2)+
            untilBuild = provider { null }  // no upper bound
        }
    }

    // JetBrains Marketplace publishing
    // Token: Settings > Manage Accounts > generate token with "Plugin Upload" scope
    // Channels: "stable" (default), "eap" (early access)
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(providers.environmentVariable("PUBLISH_CHANNEL").getOrElse("eap"))
    }

    // Plugin verification — checks for deprecated/removed API usage
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, androidStudioVersion)
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
