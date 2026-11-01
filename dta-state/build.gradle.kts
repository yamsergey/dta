plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Feature 016: an app's own source for the DTA timeline. Plain JVM bytecode for Java 11, so
// any Android app can depend on it; no dependency on DTA itself.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
