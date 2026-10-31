package io.yamsergey.dta.inject

/**
 * What the init script injects (010 contracts/injection.md, v2): the sidekick [version] of this
 * DTA release, and the Gradle properties that name the report file, turn the Compose tooling
 * data off (`false`) and name a run request (run mode, `dta run`). A `-SNAPSHOT` version also
 * resolves from the snapshot repository and `mavenLocal()`, never cached.
 */
data class InjectionConfig(
    val version: String,
    val reportProperty: String = REPORT_PROPERTY,
    val toolingProperty: String = TOOLING_PROPERTY,
    val runProperty: String = RUN_PROPERTY,
) {
    init {
        // The version is written into the script: only what a Maven version can hold.
        require(VERSION.matches(version)) { "not a sidekick version: $version" }
        listOf(reportProperty, toolingProperty, runProperty).forEach { require(PROPERTY.matches(it)) { "not a Gradle property name: $it" } }
    }

    val snapshot: Boolean get() = version.endsWith("-SNAPSHOT")

    val coordinate: String get() = "$GROUP:$ARTIFACT:$version"

    companion object {
        const val GROUP = "io.github.yamsergey"
        const val ARTIFACT = "dta-sidekick"
        const val REPORT_PROPERTY = "dta.inject.report"
        const val TOOLING_PROPERTY = "dta.inject.tooling"
        const val RUN_PROPERTY = "dta.run.request"
        const val TOOLING_COORDINATE = "androidx.compose.ui:ui-tooling-data"

        private val VERSION = Regex("[A-Za-z0-9._+-]+")
        private val PROPERTY = Regex("[A-Za-z0-9._-]+")

        /**
         * The config for a DTA release: an EAP build (`0.10.0-SNAPSHOT.42`, unique per run)
         * uses the libraries' mutable `0.10.0-SNAPSHOT`.
         */
        fun forRelease(release: String): InjectionConfig = InjectionConfig(release.replace(Regex("-SNAPSHOT\\.\\d+$"), "-SNAPSHOT"))
    }
}
