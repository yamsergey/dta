import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

// The Android Studio plugin (feature 009): one more client of the shared daemon. It binds
// dta-present's view states to Swing and adds what only an IDE can do (research R3).
repositories {
    // Project repositories replace the settings' ones, so Maven Central is repeated here.
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        // Platform interfaces have JVM default methods: no bridges to them in our classes
        // (the verifier reports those bridges as uses of internal and deprecated API).
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

/**
 * Compiled and verified against the lowest supported Android Studio (research R3): building
 * against a newer one would let APIs absent from 252 slip in. The installed Android Studio
 * is used to run the plugin (`runStudio`).
 */
val androidStudioVersion = "2025.2.2.7"

/** The current Android Studio release, verified as well so a newer IDE is checked before users get it. */
val currentAndroidStudioVersion = "2026.2.2.8"

/** The previous patch of the current release, still widely installed: it lacks platform API that 2026.2.2 has. */
val previousAndroidStudioVersion = "2026.2.1.8"

fun findLocalAndroidStudio(): String? {
    providers.gradleProperty("androidStudioPath").orNull?.let { return it }
    providers.environmentVariable("ANDROID_STUDIO_PATH").orNull?.let { return it }
    return listOf(
        "/Applications/Android Studio.app",
        "${System.getProperty("user.home")}/android-studio",
    ).firstOrNull { file(it).exists() }
}

dependencies {
    intellijPlatform {
        androidStudio(androidStudioVersion)
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    implementation(project(":dta-client"))
    implementation(project(":dta-present"))
    implementation(project(":dta-mcp"))
    implementation(project(":dta-inject"))

    testImplementation(testFixtures(project(":dta-mcp")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The platform's light tests still run on JUnit 4.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${libs.versions.junit.jupiter.get()}")
}

/**
 * The daemon's full runtime classpath, its own kotlin-stdlib and coroutines included, shipped
 * in the plugin's `daemon/` directory (research R4): the plugin starts the shared daemon as a
 * separate process from these jars, so it works without the CLI installed.
 */
val daemonRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    daemonRuntime(project(":dta-daemon"))
}

// The IDE supplies kotlin-stdlib, kotlinx-coroutines and SLF4J (Jetty logs through the IDE's);
// bundling our own breaks the plugin.
configurations.named("runtimeClasspath") {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.yamsergey.dta"
        name = "DTA - Development Tools for Android"
        version = project.version.toString()
        vendor {
            name = "Sergey Yamshchikov"
            url = "https://github.com/yamsergey/dta"
        }
        ideaVersion {
            sinceBuild = "252" // Android Studio 2025.2.2, the original's range
            untilBuild = provider { null }
        }
        changeNotes = providers.provider {
            "<p>DTA ${project.version}: Network (HTTP and WebSocket in one live list), Layout, network Script and protobuf " +
                "Schemas, DTA added to debug builds run from the IDE, and DTA's tools served to agents over HTTP.</p>"
        }
    }

    // JetBrains Marketplace (as the original): the token from the repository secret, channel eap unless stable.
    // Not signed: the original wasn't, and the repository holds no signing secrets.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.environmentVariable("PUBLISH_CHANNEL").orElse("eap").map { listOf(it) }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.AndroidStudio, androidStudioVersion)
            create(IntelliJPlatformType.AndroidStudio, previousAndroidStudioVersion)
            create(IntelliJPlatformType.AndroidStudio, currentAndroidStudioVersion)
        }
    }

    // Requires launching the full IDE, which fails with local() installs.
    buildSearchableOptions = false
}

// Every sandbox: the one the ZIP is built from and the ones runStudio and the tests run in.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    from(daemonRuntime) { into(intellijPlatform.projectName.map { "$it/daemon" }) }
}

/**
 * Fails the build when the plugin ZIP cannot start the daemon (`daemon/` must hold DaemonMain's
 * jar), or when its parts disagree on the version (FR-025): the plugin's own version, the version
 * resource the daemon's identity and the injected sidekick come from (dta-client's), and the
 * bundled daemon must all be [expectedVersion].
 */
abstract class VerifyDaemonBundle : DefaultTask() {
    @get:InputFile abstract val pluginZip: RegularFileProperty
    @get:Input abstract val expectedVersion: Property<String>

    @TaskAction
    fun verify() {
        val expected = expectedVersion.get()
        val name = pluginZip.get().asFile.name
        ZipFile(pluginZip.get().asFile).use { zip ->
            val jars = zip.entries().asSequence().map { it.name }.filter { it.endsWith(".jar") }.toList()
            // Feature 029: the robot IDE's robot-server is for test runs only, never shipped.
            jars.filter { "robot" in it.substringAfterLast('/').lowercase() }.takeIf { it.isNotEmpty() }?.let {
                throw GradleException("$name: test-only robot jars in the plugin ZIP: $it")
            }
            val daemonJars = jars.filter { Regex("[^/]+/daemon/[^/]+\\.jar").matches(it) }
            val main = daemonJars.firstOrNull { it.substringAfterLast('/').startsWith("dta-daemon-") }
                ?: throw GradleException("$name: daemon/ has no dta-daemon jar (found $daemonJars)")
            val mainEntries = entries(zip, main)
            if ("io/yamsergey/dta/daemon/server/DaemonMain.class" !in mainEntries.keys) throw GradleException("$main has no DaemonMain")

            val versions = linkedMapOf<String, String?>()
            versions["daemon/ dta-daemon jar"] = main.substringAfterLast('/').removePrefix("dta-daemon-").removeSuffix(".jar")
            val studio = jars.firstOrNull { Regex("[^/]+/lib/dta-studio-[^/]+\\.jar").matches(it) } ?: throw GradleException("$name: no dta-studio jar in lib/")
            versions["plugin.xml <version>"] = entries(zip, studio)["META-INF/plugin.xml"]?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1) }
            val client = jars.firstOrNull { Regex("[^/]+/lib/dta-client-[^/]+\\.jar").matches(it) } ?: throw GradleException("$name: no dta-client jar in lib/")
            versions["dta-version.properties (daemon identity, injected sidekick)"] = entries(zip, client)["dta-version.properties"]?.let { text ->
                Properties().apply { load(text.reader()) }.getProperty("version")
            }
            val wrong = versions.filterValues { it != expected }
            if (wrong.isNotEmpty()) {
                throw GradleException("$name: versions disagree with $expected (FR-025): " + wrong.entries.joinToString("; ") { "${it.key} = ${it.value}" })
            }
        }
    }

    /** The text entries of a jar inside the ZIP that this check reads. */
    private fun entries(zip: ZipFile, jar: String): Map<String, String> =
        ZipInputStream(zip.getInputStream(zip.getEntry(jar))).use { input ->
            generateSequence { input.nextEntry }.associate { entry ->
                entry.name to if (entry.name.endsWith(".xml") || entry.name.endsWith(".properties")) input.readBytes().decodeToString() else ""
            }
        }
}

val verifyDaemonBundle = tasks.register<VerifyDaemonBundle>("verifyDaemonBundle") {
    pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    expectedVersion = project.version.toString()
}
tasks.named("buildPlugin") { finalizedBy(verifyDaemonBundle) }
tasks.named("check") { dependsOn(verifyDaemonBundle) }

tasks.test {
    useJUnitPlatform()
}

intellijPlatformTesting {
    runIde {
        register("runStudio") {
            findLocalAndroidStudio()?.let { localPath = file(it) }
        }
        // Feature 029: the robot IDE — the installed Studio with this plugin and JetBrains'
        // robot-server, own sandbox, robot port on localhost; never part of the plugin ZIP.
        register("runStudioForUiTests") {
            findLocalAndroidStudio()?.let { localPath = file(it) }
            task {
                val port = providers.gradleProperty("robotPort").orElse("8082")
                val project = providers.gradleProperty("robotProject")
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=${port.get()}",
                        "-Drobot-server.host.public=false",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Didea.trust.all.projects=true",
                        "-Dide.show.tips.on.startup.default.value=false",
                        "-Didea.initially.ask.config=never",
                        "-Dide.newUsersOnboarding=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider { listOfNotNull(project.orNull) }
            }
            plugins { robotServerPlugin() }
        }
    }
}
