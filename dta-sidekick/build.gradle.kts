plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "io.yamsergey.dta.sidekick"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Native build configuration
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }

        // Build for all common ABIs
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // CMake configuration for native code
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Resolvable configuration that pulls the shim's dex'd jar artifact (NOT its
// .class jar) so we can bundle it as an Android asset. The shim publishes
// this artifact via its `shimDexJar` outgoing configuration.
val shimDexJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // The shim provides HookDispatcher / HookRegistry / MethodHook to the
    // bootstrap classpath at runtime (extracted from assets/dta-shim.jar
    // and added via JVMTI AddToBootstrapClassLoaderSearch). compileOnly
    // here so the published AAR's POM doesn't reference dta-sidekick-shim
    // as a Maven dependency — the shim is an internal implementation
    // detail bundled as a dex'd asset.
    //
    // The asset is installed onto the bootstrap classloader by
    // BootstrapShimProvider (a ContentProvider with high initOrder) before
    // AndroidX Startup runs, so SidekickInitializer's class link (which
    // transitively references MethodHook via the hook classes it
    // instantiates) finds the shim in bootstrap and resolves cleanly.
    compileOnly(project(":dta-sidekick-shim"))
    // Pull the dex'd shim jar so we can copy it into the AAR's assets.
    shimDexJar(project(mapOf("path" to ":dta-sidekick-shim", "configuration" to "shimDexJar")))

    // Lombok for boilerplate reduction (builders, with methods, etc.)
    compileOnly(libs.sugar.lombok)
    annotationProcessor(libs.sugar.lombok)

    // AndroidX Startup for auto-initialization
    implementation("androidx.startup:startup-runtime:1.1.1")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // MessagePack for efficient binary event serialization
    implementation("org.msgpack:msgpack-core:0.9.8")

    // DEX bytecode manipulation for runtime hooks
    implementation("com.android.tools.smali:smali-dexlib2:3.0.7")

    // Compose UI (compileOnly - provided by host app)
    compileOnly("androidx.compose.ui:ui:1.5.0")
    compileOnly("androidx.compose.runtime:runtime:1.5.0")

    // OkHttp (compileOnly - provided by host app for network inspection)
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
}

// Generate version.properties from centralized version
val versionPropsDir = layout.buildDirectory.dir("generated/resources")
val dtaVersionValue = rootProject.findProperty("dtaVersion")?.toString() ?: "0.0.0"

abstract class GenerateVersionPropertiesTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        File(outDir, "version.properties").writeText("version=${version.get()}\n")
    }
}

tasks.register<GenerateVersionPropertiesTask>("generateVersionProperties") {
    version.set(dtaVersionValue)
    outputDir.set(versionPropsDir)
}

/**
 * Stage the shim's dex'd jar at a known path so AGP picks it up via the
 * generated-assets srcDir below. Without this, the AAR doesn't ship the
 * shim and runtime AddToBootstrapClassLoaderSearch has nothing to add.
 */
val shimAssetsDir = layout.buildDirectory.dir("generated/shim-assets")

val copyShimDexJar = tasks.register<Copy>("copyShimDexJar") {
    description = "Stage the dex'd shim jar as an Android asset"
    group = "build"
    from(shimDexJar)
    into(shimAssetsDir)
    rename { "dta-shim.jar" }
}

android.sourceSets {
    getByName("main") {
        resources.srcDirs(versionPropsDir)
        assets.srcDirs(shimAssetsDir)
    }
}

tasks.named("preBuild") {
    dependsOn("generateVersionProperties", copyShimDexJar)
}

val dtaVersion = project.property("dtaVersion") as String

mavenPublishing {
    publishToMavenCentral()
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
    coordinates("io.github.yamsergey", "dta-sidekick", dtaVersion)

    pom {
        name.set("DTA Sidekick")
        description.set("Android library for runtime inspection - UI hierarchy, network, and WebSocket monitoring")
        url.set("https://github.com/yamsergey/dta")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("yamsergey")
                name.set("Sergey Yamshchikov")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/yamsergey/dta.git")
            developerConnection.set("scm:git:ssh://github.com/yamsergey/dta.git")
            url.set("https://github.com/yamsergey/dta")
        }
    }
}
