package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.layout.compose.CapabilityOutcome
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence

/** Computes the `layout` capability status from the SAME presence probes the
 * capture uses — the single source of truth that keeps identity and capture in
 * agreement (spec US2.4, contract §5). */
object LayoutCapability {

    const val NAME = "layout"

    fun status(presence: ComposePresence): CapabilityStatus =
        when (presence.capabilityOutcome()) {
            CapabilityOutcome.FULL ->
                CapabilityStatus(NAME, CapabilityStatus.Status.AVAILABLE, levels = levels(presence))
            CapabilityOutcome.DEGRADED ->
                CapabilityStatus(
                    NAME,
                    CapabilityStatus.Status.DEGRADED,
                    "compose present, enrichment unavailable: ${presence.tier2Skip}",
                    levels = levels(presence),
                )
        }

    /** Detail levels the capture can serve (feature 003): richer levels need
     * working tooling, the same gate the capture uses. */
    fun levels(presence: ComposePresence): List<DetailLevel> =
        if (presence.tier2Available) DetailLevel.entries.toList() else listOf(DetailLevel.SEMANTIC)
}
