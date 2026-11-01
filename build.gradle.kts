plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.intellij.platform) apply false
}

allprojects {
    group = "io.github.yamsergey"
    version = providers.gradleProperty("dtaVersion").get()
}

/**
 * Published libraries (feature 008, research R9): what an app build (the sidekick and its
 * runtime companions) or a client (daemon, client) needs. The CLI ships as a distribution,
 * not a library. Each module sets its own name and description.
 */
val publishedModules = mapOf(
    "dta-protocol" to ("DTA Protocol" to "Wire protocol shared by the DTA sidekick, daemon and clients"),
    "dta-sidekick" to ("DTA Sidekick" to "Android library for runtime inspection: layout tree, network capture and scripting"),
    "dta-sidekick-rhino" to ("DTA Sidekick Rhino" to "Mozilla Rhino relocated for the DTA sidekick's network scripts"),
    "dta-daemon" to ("DTA Daemon" to "Host-side DTA daemon: device connections, layout, network, scripts, protobuf decoding"),
    "dta-client" to ("DTA Client" to "Client of the DTA daemon process: launcher and RPC client"),
    "dta-state" to ("DTA State" to "An app's own facets, events and causes for the DTA timeline; does nothing without the sidekick"),
    "dta-state-noop" to ("DTA State (no-op)" to "The dta-state API with empty bodies, for release builds"),
)

subprojects {
    val meta = publishedModules[name] ?: return@subprojects
    apply(plugin = "com.vanniktech.maven.publish")
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral()
        if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
        pluginManager.withPlugin("com.android.library") {
            // AGP's Dokka javadoc task fails on the sidekick; an empty javadoc jar satisfies Central.
            configure(
                com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
                    com.vanniktech.maven.publish.JavadocJar.Empty(),
                    com.vanniktech.maven.publish.SourcesJar.Sources(),
                    "release",
                ),
            )
        }
        coordinates("io.github.yamsergey", name, version.toString())
        pom {
            this.name.set(meta.first)
            description.set(meta.second)
            url.set("https://github.com/yamsergey/dta")
            licenses {
                license {
                    this.name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("yamsergey")
                    this.name.set("Sergey Yamshchikov")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/yamsergey/dta.git")
                developerConnection.set("scm:git:ssh://github.com/yamsergey/dta.git")
                url.set("https://github.com/yamsergey/dta")
            }
        }
    }
}
