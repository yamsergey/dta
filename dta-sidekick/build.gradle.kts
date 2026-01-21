plugins {
    id("com.android.library") version "8.7.3"
    id("maven-publish")
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

android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.yamsergey.dta"
                artifactId = "sidekick"
                version = project.property("dtaVersion") as String
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
