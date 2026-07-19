package io.yamsergey.dta.sidekick.layout

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
                CapabilityStatus(NAME, CapabilityStatus.Status.AVAILABLE)
            CapabilityOutcome.DEGRADED ->
                CapabilityStatus(
                    NAME,
                    CapabilityStatus.Status.DEGRADED,
                    "compose present, enrichment unavailable: ${presence.tier2Skip}",
                )
        }
}
