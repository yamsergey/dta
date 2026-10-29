import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
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

application {
    applicationName = "dta"
    mainClass = "io.yamsergey.dta.cli.MainKt"
}

dependencies {
    implementation(project(":dta-client"))
    // The daemon process is started from this distribution's own lib/ (research R3).
    implementation(project(":dta-daemon"))
    implementation(project(":dta-mcp"))
    implementation(project(":dta-present"))
    implementation(libs.clikt)

    testImplementation(testFixtures(project(":dta-mcp")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * dta-version.properties: version, UTC build time and git SHA (research R4/R9). The build
 * time orders two builds of the same version, so a rebuilt CLI replaces an older daemon.
 */
abstract class VersionResource : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val build = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
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
    outputDir = layout.buildDirectory.dir("generated/version")
    // A new build time on every build is the point; never reuse an old file.
    outputs.upToDateWhen { false }
}

sourceSets.main {
    resources.srcDir(versionResource)
}

tasks.test {
    useJUnitPlatform()
}

/** The Claude Code plugin ships with the CLI it starts: its version must be the build's (T050). */
val pluginManifest = rootProject.layout.projectDirectory.file("plugins/dta/.claude-plugin/plugin.json")
val checkPluginVersion = tasks.register("checkPluginVersion") {
    val manifest = providers.fileContents(pluginManifest).asText
    val expected = project.version.toString()
    inputs.property("expected", expected)
    doLast {
        val actual = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(manifest.get())?.groupValues?.get(1)
        // Development builds (0.10.0-SNAPSHOT.<n>) ship the plugin of their release line.
        check(actual == expected.substringBefore('-')) { "plugins/dta/.claude-plugin/plugin.json version is $actual, dtaVersion is $expected — update the plugin manifest" }
    }
}
tasks.named("check") { dependsOn(checkPluginVersion) }
