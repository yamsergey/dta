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

dependencies {
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

android.sourceSets {
    getByName("main") {
        resources.srcDirs(versionPropsDir)
    }
}

tasks.named("preBuild") {
    dependsOn("generateVersionProperties")
}

val dtaVersion = project.property("dtaVersion") as String

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
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
                name.set("GNU Lesser General Public License v3.0")
                url.set("https://www.gnu.org/licenses/lgpl-3.0.en.html")
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
