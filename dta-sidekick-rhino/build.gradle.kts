plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

// Rhino (MPL 2.0) relocated under our namespace, so it can ship inside the sidekick
// without colliding with an app's own Rhino (constitution I: vendored, own namespace).
// Rhino sits in a private configuration: consumers get only the relocated jar, never
// org.mozilla:rhino as a transitive dependency.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val shade: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    shade(libs.rhino)
}

tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(shade)
    relocate("org.mozilla.javascript", "io.yamsergey.dta.vendor.rhino")
    relocate("org.mozilla.classfile", "io.yamsergey.dta.vendor.rhino.classfile")
    // Shell and debugger (Swing) are not used on Android.
    exclude("org/mozilla/javascript/tools/**")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF", "META-INF/maven/**")
    rename("LICENSE.txt", "rhino-LICENSE.txt")
}

// Consumers get only the relocated jar. The secondary variants (classes/resources
// directories) must go too: Android consumers prefer them and would get an empty module.
for (name in listOf("runtimeElements", "apiElements")) {
    configurations.named(name) {
        outgoing.artifacts.clear()
        outgoing.variants.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}
