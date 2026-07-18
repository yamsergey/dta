package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker

enum class RootProviderKind { WINDOW_INSPECTOR, REFLECTION }

data class RootProviderSelection(
    val kind: RootProviderKind,
    val degradation: DegradationMarker?,
)

/**
 * Chooses how to discover window roots by API level, so the API<29 reflection
 * path (unreachable on modern emulators) is still deviceless-testable and its
 * degradation is guaranteed to surface (fixes analyze C2).
 */
object RootProviderSelector {
    fun select(apiLevel: Int): RootProviderSelection =
        if (apiLevel >= 29) {
            RootProviderSelection(RootProviderKind.WINDOW_INSPECTOR, null)
        } else {
            RootProviderSelection(
                RootProviderKind.REFLECTION,
                DegradationMarker("layout", DegradationCodes.LEGACY_ROOT_DISCOVERY),
            )
        }
}
