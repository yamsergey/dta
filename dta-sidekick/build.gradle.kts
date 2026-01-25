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
                // Don't set groupId - let JitPack derive it as com.github.yamsergey.dta
                // JitPack multi-module format: com.github.{user}.{repo}:{module}:{version}
                artifactId = "dta-sidekick"
                version = project.property("dtaVersion") as String

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
                }
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
