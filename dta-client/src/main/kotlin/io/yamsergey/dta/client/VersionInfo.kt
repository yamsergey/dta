package io.yamsergey.dta.client

import java.util.Properties

/**
 * What this DTA build is: version, UTC build time and git SHA, from the
 * `dta-version.properties` the CLI build writes (research R9). Without the file
 * (an IDE run, a test) the version is `dev` and [missingReason] says so.
 */
data class VersionInfo(
    val version: String,
    val build: String,
    val sha: String,
    val missingReason: String? = null,
) {
    val display: String get() = "$version (build $build, $sha)"

    companion object {
        const val RESOURCE = "dta-version.properties"

        fun current(): VersionInfo =
            from(VersionInfo::class.java.classLoader.getResourceAsStream(RESOURCE)?.use { Properties().apply { load(it) } })

        fun from(props: Properties?): VersionInfo {
            val version = props?.getProperty("version")
            val build = props?.getProperty("build")
            if (version == null || build == null) {
                return VersionInfo("dev", "unknown", "unknown", "$RESOURCE not on the classpath (not an installed build)")
            }
            return VersionInfo(version, build, props.getProperty("sha") ?: "unknown")
        }
    }
}
