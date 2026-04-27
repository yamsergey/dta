/**
 * dta-sidekick-shim — minimal classes that must live on the Android bootstrap
 * classloader so JVMTI bytecode injected into boot-class methods (e.g.
 * android.app.Activity.startActivityForResult) can resolve the dispatcher
 * call site.
 *
 * <p>Why a separate module: the Android bootstrap classloader can't see
 * classes loaded by the app classloader, so an injected
 * {@code invokestatic Lio/yamsergey/dta/sidekick/jvmti/HookDispatcher;.onEnter(...)}
 * issued from a boot class hits {@code NoClassDefFoundError}. The fix —
 * borrowed from Android Studio's transport agent — is to bundle a small jar
 * alongside the agent and add it to the bootstrap classpath via JVMTI's
 * {@code AddToBootstrapClassLoaderSearch}. This module is that jar.</p>
 *
 * <p>Constraints — ANY new dependency here drags the bootstrap classloader
 * with it, which is asking for trouble:
 * <ul>
 *   <li>No third-party deps. Java stdlib + Android framework (compileOnly) only.</li>
 *   <li>No SLF4J / Jackson / Kotlin stdlib. Use {@code android.util.Log} directly.</li>
 *   <li>Code touched by the dispatch hot path must not allocate or synchronize
 *       beyond what the original handlers do — every host-app method call
 *       routes through here.</li>
 * </ul></p>
 */
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // android.util.Log + framework classes for compile-time symbol resolution.
    // Resolved at runtime against the actual platform on device — never bundled.
    val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME / ANDROID_SDK_ROOT not set — required to compile dta-sidekick-shim")
    compileOnly(files("$androidHome/platforms/android-34/android.jar"))
}

// Pinning the produced jar's name keeps the asset-bundling step in the main
// dta-sidekick AAR predictable across version bumps.
tasks.named<Jar>("jar") {
    archiveBaseName.set("dta-sidekick-shim")
    archiveVersion.set("")
}

/**
 * DEX the shim jar so Android's bootstrap classloader can load it at
 * runtime via JVMTI's AddToBootstrapClassLoaderSearch. The bootstrap
 * classloader on Android speaks DEX, not Java .class files, so this is a
 * required transformation step — without it the asset is unloadable and
 * boot-class hooks NCDFE.
 *
 * <p>Output is {@code build/dex/dta-shim.jar} containing a single
 * {@code classes.dex}. Pinned filename so the asset-bundling step in
 * dta-sidekick stays simple.</p>
 */
val dexShimJar = tasks.register<Exec>("dexShimJar") {
    dependsOn("jar")
    description = "Dex the shim jar for runtime AddToBootstrapClassLoaderSearch"
    group = "build"

    val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME / ANDROID_SDK_ROOT not set")
    // Pick the highest installed build-tools version so we don't pin to one
    // that may not be present on a contributor's machine. d8 itself has been
    // stable for years; the version mostly affects supported API levels of
    // the desugar config, which we don't use.
    val buildTools = file("$androidHome/build-tools").listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?: error("No Android build-tools installed under $androidHome/build-tools")
    val d8 = file("${buildTools.absolutePath}/d8")

    val inputJar = layout.buildDirectory.file("libs/dta-sidekick-shim.jar")
    val outputJar = layout.buildDirectory.file("dex/dta-shim.jar")

    inputs.file(inputJar)
    outputs.file(outputJar)

    doFirst {
        outputJar.get().asFile.parentFile.mkdirs()
    }

    // --min-api 28 matches dta-sidekick's minSdk and, crucially, prevents
    // default-interface-method desugaring. Without it, d8 defaults to
    // --min-api 1, which moves default method bodies to a $-CC companion
    // class and leaves the interface methods PUBLIC ABSTRACT. Implementing
    // classes in the AAR (compiled with minSdk=28, no desugaring) rely on
    // those default impls — at runtime, INVOKEINTERFACE on the
    // bootstrap-loaded MethodHook then throws AbstractMethodError because
    // the bootstrap copy has no body. The two sides must agree on whether
    // defaults are desugared; minSdk 28 is the cleanest match.
    commandLine = listOf(
        d8.absolutePath,
        "--release",
        "--min-api", "28",
        "--output", outputJar.get().asFile.absolutePath,
        inputJar.get().asFile.absolutePath
    )
}

// Surface the dex jar via a configuration that the dta-sidekick AAR
// consumes for its assets. Saves the consuming module from hard-coding
// the path through layout.buildDirectory.
val shimDexJar by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(shimDexJar.name, layout.buildDirectory.file("dex/dta-shim.jar")) {
        builtBy(dexShimJar)
    }
}
