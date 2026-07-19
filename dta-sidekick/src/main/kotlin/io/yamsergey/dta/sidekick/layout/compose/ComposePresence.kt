package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.DegradationCodes

enum class CapabilityOutcome { FULL, DEGRADED }

/**
 * The Compose gatekeeper. Contains NO compose imports — it only asks whether
 * compose classes exist and what versions are on the classpath, so a
 * Views-only app never loads a single compose class (constitution I). The
 * Tier-1/Tier-2 capture classes are loaded only after this says yes.
 *
 * Injectable [classPresent] / [readVersion] make the whole matrix deviceless.
 */
class ComposePresence(
    private val classPresent: (String) -> Boolean = { className ->
        try {
            Class.forName(className, false, ComposePresence::class.java.classLoader)
            true
        } catch (_: Throwable) {
            false
        }
    },
    private val readVersion: (String) -> String? = ::readClasspathVersion,
) {
    val composePresent: Boolean by lazy { classPresent(COMPOSE_UI_MARKER) }

    private val toolingPresent: Boolean by lazy { classPresent(TOOLING_DATA_MARKER) }

    /** Non-null when Tier 2 is NOT available and compose IS present — the code
     * to attach to the response so the absence is never silent. */
    val tier2Skip: String? by lazy {
        when {
            !composePresent -> null // no compose at all: nothing to enrich, nothing to report
            !toolingPresent -> DegradationCodes.COMPOSE_TOOLING_ABSENT
            !versionsSane() -> DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW
            else -> null
        }
    }

    val tier2Available: Boolean by lazy { composePresent && toolingPresent && versionsSane() }

    fun capabilityOutcome(): CapabilityOutcome =
        if (!composePresent || tier2Available) CapabilityOutcome.FULL else CapabilityOutcome.DEGRADED

    private fun versionsSane(): Boolean {
        val ui = readVersion("compose-ui") ?: return false
        val tooling = readVersion("ui-tooling-data") ?: return false
        return ui == tooling
    }

    companion object {
        const val COMPOSE_UI_MARKER = "androidx.compose.ui.platform.AndroidComposeView"
        // SlotTreeKt carries asTree/mapTree — the entry point Tier-2 actually calls.
        const val TOOLING_DATA_MARKER = "androidx.compose.ui.tooling.data.SlotTreeKt"

        /** Reads the AndroidX-embedded version file for an artifact, e.g.
         * `META-INF/androidx.compose.ui_ui.version` — the same signal Studio
         * uses for its compatibility probe. */
        private fun readClasspathVersion(artifact: String): String? {
            val resource = when (artifact) {
                "compose-ui" -> "META-INF/androidx.compose.ui_ui.version"
                "ui-tooling-data" -> "META-INF/androidx.compose.ui_ui-tooling-data.version"
                else -> return null
            }
            return ComposePresence::class.java.classLoader
                ?.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                ?.ifEmpty { null }
        }
    }
}
