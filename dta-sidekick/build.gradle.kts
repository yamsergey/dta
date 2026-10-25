plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.yamsergey.dta.sidekick"
    compileSdk = 34

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        }
    }

    // libdta-agent.so: JVMTI agent + vendored slicer (network capture hooks).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "VERSION", "\"${project.version}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

// The hook shim is NOT a runtime dependency: it is dexed (d8 --min-api 28) into
// assets/dta-shim.jar and added to the bootstrap class path by the agent, so boot
// classes (java.net.URL) can call it. Sidekick compiles against it only.
val shimClasses: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

abstract class DexShimTask : DefaultTask() {
    @get:InputFiles
    abstract val shimJars: ConfigurableFileCollection

    @get:InputFile
    abstract val d8: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun dex() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        exec.exec {
            commandLine(
                listOf(d8.get().asFile.absolutePath, "--release", "--min-api", "28",
                    "--output", File(out, "dta-shim.jar").absolutePath) +
                    shimJars.files.map { it.absolutePath },
            )
        }
    }
}

val dexShim = tasks.register<DexShimTask>("dexShim") {
    shimJars.from(shimClasses)
    d8.set(androidComponents.sdkComponents.sdkDirectory.map {
        it.file("build-tools/${android.buildToolsVersion}/d8")
    })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(dexShim, DexShimTask::outputDir)
    }
}

dependencies {
    api(project(":dta-protocol"))
    compileOnly(project(":dta-sidekick-shim"))
    shimClasses(project(":dta-sidekick-shim"))
    implementation(libs.androidx.startup)

    // compileOnly ONLY: sidekick reflects/probes for Compose at runtime and the
    // host app supplies the real version. These MUST NOT appear in the AAR's
    // runtime classpath — verified by DependencyLeakTest (constitution I).
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.ui.tooling.data)
    // compileOnly ONLY, same rule: the host app supplies OkHttp; capture code that
    // touches its types loads only after a presence probe.
    compileOnly(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.ui.tooling.data)
    testImplementation(project(":dta-sidekick-shim"))
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
