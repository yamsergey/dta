plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    api(project(":dta-protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.java.util)
    implementation(libs.opentelemetry.proto)
    implementation(libs.wire.schema)

    testImplementation(project(":dta-client"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
