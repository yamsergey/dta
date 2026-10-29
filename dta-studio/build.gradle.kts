import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

// The Android Studio plugin (feature 009): one more client of the shared daemon. It binds
// dta-present's view states to Swing and adds what only an IDE can do (research R3).
repositories {
    // Project repositories replace the settings' ones, so Maven Central is repeated here.
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

/**
 * Compiled and verified against the lowest supported Android Studio (research R3): building
 * against a newer one would let APIs absent from 252 slip in. The installed Android Studio
 * is used to run the plugin (`runStudio`).
 */
val androidStudioVersion = "2025.2.2.7"

fun findLocalAndroidStudio(): String? {
    providers.gradleProperty("androidStudioPath").orNull?.let { return it }
    providers.environmentVariable("ANDROID_STUDIO_PATH").orNull?.let { return it }
    return listOf(
        "/Applications/Android Studio.app",
        "${System.getProperty("user.home")}/android-studio",
    ).firstOrNull { file(it).exists() }
}

dependencies {
    intellijPlatform {
        androidStudio(androidStudioVersion)
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.gradle")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    implementation(project(":dta-client"))
    implementation(project(":dta-present"))
    implementation(project(":dta-mcp"))
    implementation(project(":dta-inject"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The platform's test framework still runs on JUnit 4.
    testImplementation("junit:junit:4.13.2")
}

// The IDE supplies kotlin-stdlib and kotlinx-coroutines; bundling our own breaks the plugin.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.yamsergey.dta"
        name = "DTA — Development Tools for Android"
        version = project.version.toString()
        vendor {
            name = "Sergey Yamshchikov"
            url = "https://github.com/yamsergey/dta"
        }
        ideaVersion {
            sinceBuild = "252" // Android Studio 2025.2.2, the original's range
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.AndroidStudio, androidStudioVersion)
        }
    }

    // Requires launching the full IDE, which fails with local() installs.
    buildSearchableOptions = false
}

intellijPlatformTesting {
    runIde {
        register("runStudio") {
            findLocalAndroidStudio()?.let { localPath = file(it) }
        }
    }
}
