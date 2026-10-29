import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Client side of the daemon process (feature 008): launcher + RPC client. Shared by the
// CLI, the MCP server and the Android Studio plugin, so it carries no CLI dependencies.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    api(project(":dta-protocol"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * dta-version.properties: version, UTC build time and git SHA (research R4/R9 of 008, R5 of
 * 009). The build time orders two builds of the same version, so a rebuilt CLI replaces an
 * older daemon. It lives in this jar because the CLI and the plugin both ship it; CI sets
 * DTA_BUILD_TIME once per run so the CLI and the plugin of one release share one identity.
 */
abstract class VersionResource : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha: Property<String>
    @get:Input @get:Optional abstract val buildTime: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val fixed = buildTime.orNull?.let {
            runCatching { Instant.parse(it) }.getOrElse { _ -> throw GradleException("DTA_BUILD_TIME '$it' is not an ISO-8601 instant (e.g. 2026-10-29T12:00:00Z)") }
        }
        val build = (fixed ?: Instant.now()).truncatedTo(ChronoUnit.SECONDS).toString()
        val file = outputDir.get().file("dta-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${version.get()}\nbuild=$build\nsha=${sha.get()}\n")
    }
}

val gitSha = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val versionResource = tasks.register<VersionResource>("versionResource") {
    version = project.version.toString()
    sha = gitSha.orElse("unknown")
    buildTime = providers.environmentVariable("DTA_BUILD_TIME")
    outputDir = layout.buildDirectory.dir("generated/version")
    // Without DTA_BUILD_TIME a new build time on every build is the point; never reuse an old file.
    outputs.upToDateWhen { false }
}

sourceSets.main {
    resources.srcDir(versionResource)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("dta.expectedVersion", project.version.toString())
}
