package io.yamsergey.dta.inject

/**
 * What the init script injects (contracts/injection.md): the sidekick [version] of this DTA
 * release, and the Gradle property that names the report file. A `-SNAPSHOT` version also
 * resolves from the snapshot repository and `mavenLocal()`, never cached.
 */
data class InjectionConfig(val version: String, val reportProperty: String = REPORT_PROPERTY) {
    init {
        // The version is written into the script: only what a Maven version can hold.
        require(VERSION.matches(version)) { "not a sidekick version: $version" }
        require(PROPERTY.matches(reportProperty)) { "not a Gradle property name: $reportProperty" }
    }

    val snapshot: Boolean get() = version.endsWith("-SNAPSHOT")

    val coordinate: String get() = "$GROUP:$ARTIFACT:$version"

    companion object {
        const val GROUP = "io.github.yamsergey"
        const val ARTIFACT = "dta-sidekick"
        const val REPORT_PROPERTY = "dta.inject.report"

        private val VERSION = Regex("[A-Za-z0-9._+-]+")
        private val PROPERTY = Regex("[A-Za-z0-9._-]+")

        /**
         * The config for a DTA release: an EAP build (`0.10.0-SNAPSHOT.42`, unique per run)
         * uses the libraries' mutable `0.10.0-SNAPSHOT`.
         */
        fun forRelease(release: String): InjectionConfig = InjectionConfig(release.replace(Regex("-SNAPSHOT\\.\\d+$"), "-SNAPSHOT"))
    }
}
