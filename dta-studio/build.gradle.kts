import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
        // Platform interfaces have JVM default methods: no bridges to them in our classes
        // (the verifier reports those bridges as uses of internal and deprecated API).
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
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

    testImplementation(testFixtures(project(":dta-mcp")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The platform's light tests still run on JUnit 4.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${libs.versions.junit.jupiter.get()}")
}

/**
 * The daemon's full runtime classpath, its own kotlin-stdlib and coroutines included, shipped
 * in the plugin's `daemon/` directory (research R4): the plugin starts the shared daemon as a
 * separate process from these jars, so it works without the CLI installed.
 */
val daemonRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    daemonRuntime(project(":dta-daemon"))
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
        name = "DTA - Development Tools for Android"
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

// Every sandbox: the one the ZIP is built from and the ones runStudio and the tests run in.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    from(daemonRuntime) { into(intellijPlatform.projectName.map { "$it/daemon" }) }
}

/** Fails the build when the plugin ZIP cannot start the daemon: `daemon/` must hold DaemonMain's jar. */
abstract class VerifyDaemonBundle : DefaultTask() {
    @get:InputFile abstract val pluginZip: RegularFileProperty

    @TaskAction
    fun verify() {
        ZipFile(pluginZip.get().asFile).use { zip ->
            val daemonJars = zip.entries().asSequence().filter { Regex("[^/]+/daemon/[^/]+\\.jar").matches(it.name) }.toList()
            val main = daemonJars.firstOrNull { it.name.substringAfterLast('/').startsWith("dta-daemon-") }
                ?: throw GradleException("${pluginZip.get().asFile.name}: daemon/ has no dta-daemon jar (found ${daemonJars.map { it.name }})")
            ZipInputStream(zip.getInputStream(main)).use { jar ->
                val entries = generateSequence { jar.nextEntry }.map { it.name }.toSet()
                if ("io/yamsergey/dta/daemon/server/DaemonMain.class" !in entries) throw GradleException("${main.name} has no DaemonMain")
            }
        }
    }
}

val verifyDaemonBundle = tasks.register<VerifyDaemonBundle>("verifyDaemonBundle") {
    pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
}
tasks.named("buildPlugin") { finalizedBy(verifyDaemonBundle) }
tasks.named("check") { dependsOn(verifyDaemonBundle) }

tasks.test {
    useJUnitPlatform()
}

intellijPlatformTesting {
    runIde {
        register("runStudio") {
            findLocalAndroidStudio()?.let { localPath = file(it) }
        }
    }
}
