plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
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

dependencies {
    intellijPlatform {
        local("/Applications/Android Studio.app")  // Panda 2025.2.2
        bundledPlugin("org.jetbrains.android")
        pluginVerifier()
        instrumentationTools()
    }

    implementation(project(":dta-daemon"))
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
            sinceBuild = "252"
            untilBuild = provider { null }  // no upper bound
        }
    }
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }

    // buildSearchableOptions requires launching the full IDE, which fails with local() installs
    buildSearchableOptions {
        enabled = false
    }
}
