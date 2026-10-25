package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.FallbackNode
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/** A host's tooling-backed capture at [level], built all-or-none. */
data class ToolingOutcome(
    val walk: ComposeWalkResult,
    val level: DetailLevel,
    /** false when tooling ran but carried no source locations at all. */
    val sourceInfoPresent: Boolean = true,
)

/** The level one Compose host was actually served at, its walk with the
 * host-root markers applied, and whether its tooling read failed. */
data class HostResolution(
    val walk: ComposeWalkResult,
    val served: DetailLevel,
    val enrichmentFailed: Boolean,
)

/**
 * Per-host level resolution (feature 003 US2, contract §3). Pure. A host is
 * served from its tooling outcome when that succeeded, else from the plain
 * semantics walk. Tooling data of a failed host never ships (all-or-none).
 * Every reduction below the requested level is stated on the host root.
 */
object LevelResolution {

    /** [tooling] is null when tooling is unavailable for the whole capture
     * (absent / version skew: that cause is a capture-level marker). */
    fun resolveHost(
        requested: DetailLevel,
        semantic: ComposeWalkResult,
        tooling: Result<ToolingOutcome>?,
    ): HostResolution {
        val outcome = tooling?.getOrNull()
        val failed = tooling?.isFailure == true
        val walk = outcome?.walk ?: semantic
        val served = outcome?.level ?: DetailLevel.SEMANTIC

        val codes = buildList {
            if (failed) add(DegradationCodes.COMPOSE_ENRICHMENT_FAILED)
            if (served < requested) add(DegradationCodes.COMPOSE_DETAIL_REDUCED)
            if (outcome != null && !outcome.sourceInfoPresent) add(DegradationCodes.COMPOSE_SOURCE_INFO_MISSING)
        }
        val marked = if (codes.isEmpty()) walk else walk.copy(tree = walk.tree.withMarkers(codes))
        return HostResolution(marked, served, failed)
    }

    /** The minimum level served across hosts; with no Compose host nothing
     * was reduced, so the requested level stands. */
    fun delivered(requested: DetailLevel, hosts: List<HostResolution>): DetailLevel =
        hosts.minOfOrNull { it.served } ?: requested

    private fun Node.withMarkers(codes: List<String>): Node {
        val merged = degraded.orEmpty() + codes.map { DegradationMarker(LayoutCapability.NAME, it) }
        return when (this) {
            is ComposeNode -> copy(degraded = merged)
            is ViewNode -> copy(degraded = merged)
            is FallbackNode -> copy(degraded = merged)
        }
    }
}
