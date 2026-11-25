import java.security.MessageDigest
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
 *
 * D-075: without DTA_BUILD_TIME the time is taken when the product's [sources] (or the version, the
 * SHA) change, and kept while they do not: a CLI and a plugin built one after the other from one
 * source state carry one identity and run together; an edit gives a newer one.
 *
 * D-094: the time is a function of the source state, not of when the task ran: the newest of the
 * sources' modification times (their directories' too, so a removed file counts) and the commit's
 * time. A forced re-run (--rerun-tasks), a clean, or a build killed while this task ran (its
 * output or history lost) writes the same stamp again.
 *
 * D-096: the time still follows file times, so a touch-and-revert (an editor save, a branch switch and back) gives
 * another time for one content. The identity of a source state is `content`: the sha256 of the [sources]' relative
 * paths and bytes. Two builds of one content are one build (VersionIdentity.compare: SAME); the time only orders two
 * contents.
 */
abstract class VersionResource : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha: Property<String>
    @get:Input @get:Optional abstract val buildTime: Property<String>
    @get:Input abstract val commitTime: Property<Long>
    // The sources' root, to tell a module's src/main directories from the rest.
    @get:Internal abstract val sourceRoot: DirectoryProperty
    // Directories are not sources: a tool's directory beside the modules (.kotlin/ of the Kotlin daemon) appears
    // between two builds and would restamp unchanged sources (D-075, gate 4).
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val fixed = buildTime.orNull?.let {
            runCatching { Instant.parse(it) }.getOrElse { _ -> throw GradleException("DTA_BUILD_TIME '$it' is not an ISO-8601 instant (e.g. 2026-10-29T12:00:00Z)") }
        }
        val build = (fixed ?: sourceStateTime()).truncatedTo(ChronoUnit.SECONDS).toString()
        val file = outputDir.get().file("dta-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${version.get()}\nbuild=$build\nsha=${sha.get()}\ncontent=${sourceContent()}\n")
    }

    /** sha256 over the sources sorted by their path relative to the root: each path, its length, its bytes. */
    private fun sourceContent(): String {
        val root = sourceRoot.get().asFile.toPath()
        val digest = MessageDigest.getInstance("SHA-256")
        sources.files.filter { it.isFile }.map { root.relativize(it.toPath()).joinToString("/") to it }.sortedBy { it.first }.forEach { (rel, f) ->
            val bytes = f.readBytes()
            digest.update("$rel\u0000${bytes.size}\u0000".toByteArray())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The newest change of the source state: its files, the src/main directories holding them, the commit. */
    private fun sourceStateTime(): Instant {
        val root = sourceRoot.get().asFile.toPath()
        var newest = commitTime.get() * 1000
        for (f in sources.files) {
            newest = maxOf(newest, f.lastModified())
            // Only directories inside a module's src/main: a module's own directory changes when build/ or a tool's
            // directory appears in it.
            var dir = f.parentFile?.toPath()
            while (dir != null && dir.startsWith(root) && inSources(root.relativize(dir))) {
                newest = maxOf(newest, dir.toFile().lastModified())
                dir = dir.parent
            }
        }
        return Instant.ofEpochMilli(newest)
    }

    private fun inSources(rel: java.nio.file.Path) = rel.nameCount >= 3 && rel.getName(1).toString() == "src" && rel.getName(2).toString() == "main"

}

// D-075 (gate 5): the SHA of these sources, not of the directory Gradle runs from (the harness includes this build from
// another git repository).
val gitSha = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

// D-094: the commit's time, part of the source state the stamp is taken from (0 outside git).
val gitCommitTime = providers.exec {
    workingDir(rootDir)
    commandLine("git", "log", "-1", "--format=%ct")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toLongOrNull() ?: 0L }

val versionResource = tasks.register<VersionResource>("versionResource") {
    version = project.version.toString()
    sha = gitSha.orElse("unknown")
    commitTime = gitCommitTime
    sourceRoot = rootProject.layout.projectDirectory
    buildTime = providers.environmentVariable("DTA_BUILD_TIME")
    // The product: every module's main sources and the build files that decide what goes into it (tests do not).
    sources.from(rootProject.fileTree(rootDir) {
        include("*/src/main/**", "*/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")
        exclude("**/build/**", ".intellijPlatform/**")
    })
    outputDir = layout.buildDirectory.dir("generated/version")
}

sourceSets.main {
    resources.srcDir(versionResource)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("dta.expectedVersion", project.version.toString())
}
