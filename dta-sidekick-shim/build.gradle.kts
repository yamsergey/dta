plugins {
    `java-library`
}

// Bootstrap-classloader shim (constitution I): pure Java, zero dependencies.
// Dexed by :dta-sidekick (task dexShim, d8 --min-api 28) into the AAR assets.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
