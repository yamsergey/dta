plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// The agent tools (feature 009, research R2/R9): one implementation of the catalog's tools,
// served over stdio by `dta mcp` and over loopback HTTP by the Android Studio plugin.
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
    api(project(":dta-client"))
    implementation(project(":dta-run"))
    implementation(project(":dta-present"))
    api(libs.mcp)
    implementation(libs.jetty.ee11.servlet)

    // An in-process daemon with canned answers, shared by the CLI's and the plugin's tests.
    testFixturesApi(project(":dta-daemon"))
    testFixturesApi(project(":dta-client"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
