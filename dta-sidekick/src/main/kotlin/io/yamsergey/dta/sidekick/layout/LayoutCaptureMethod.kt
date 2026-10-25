package io.yamsergey.dta.sidekick.layout

import android.os.Handler
import android.os.Looper
import android.view.View
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.DetailInfo
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutTreeParams
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence
import io.yamsergey.dta.sidekick.layout.compose.ContainerAssembly
import io.yamsergey.dta.sidekick.layout.compose.LayoutRecords
import io.yamsergey.dta.sidekick.layout.compose.ToolingTree
import io.yamsergey.dta.sidekick.layout.compose.EnrichmentOverlay
import io.yamsergey.dta.sidekick.layout.compose.SemanticsCapture
import io.yamsergey.dta.sidekick.layout.compose.ToolingEnrichment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Orchestrates a full layout capture (research D6): everything runs as ONE
 * main-thread block bounded by a timeout, so the tree is frame-consistent —
 * never a mixed-state or silently-partial tree (FR-012). Assembles the merged
 * view+compose tree, applies Tier-2 enrichment (all-or-none, degraded when
 * absent), and evaluates filters on-device.
 */
class LayoutCaptureMethod(
    private val presence: ComposePresence = ComposePresence(),
    private val viewCapture: ViewCapture = ViewCapture(),
    private val timeoutMs: Long = 5_000,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val screenProvider: () -> Screen,
) {
    fun capture(params: LayoutTreeParams = LayoutTreeParams()): LayoutCapture {
        val filter = params.filter
        val result = runOnMainThread { captureOnMain(params.detail) }
        val degraded = mutableListOf<DegradationMarker>()
        degraded += result.degraded

        val detail = DetailInfo(requested = params.detail, delivered = result.delivered)
        val windows = result.windows
        if (filter != null) {
            val matches = windows.flatMapIndexed { i, w -> filter.apply(w.root, i) }
            return LayoutCapture(
                screen = result.screen,
                source = CaptureSource.SIDEKICK,
                windows = emptyList(),
                filtersApplied = true,
                matches = matches,
                degraded = degraded.ifEmpty { null },
                detail = detail,
            )
        }
        return LayoutCapture(
            screen = result.screen,
            source = CaptureSource.SIDEKICK,
            windows = windows,
            degraded = degraded.ifEmpty { null },
            detail = detail,
        )
    }

    private class MainResult(
        val screen: Screen,
        val windows: List<Window>,
        val degraded: List<DegradationMarker>,
        val delivered: DetailLevel,
    )

    private fun captureOnMain(level: DetailLevel): MainResult {
        val degraded = mutableListOf<DegradationMarker>()
        val captured = viewCapture.captureWindows()
        val tier2 = presence.tier2Available
        presence.tier2Skip?.let { degraded += DegradationMarker(LayoutCapability.NAME, it) }

        val hosts = mutableListOf<HostResolution>()
        val windows = captured.map { window ->
            val composeWalks = window.composeHosts.mapNotNull { (hostId, hostView) ->
                captureComposeHost(hostId, hostView, tier2, level)?.also { hosts += it }?.walk
            }
            Window(window.title, window.type, window.zOrder, TreeMerger.merge(window.walk, composeWalks))
        }
        // Capture-level summary of per-host tooling failures (feature 002).
        if (hosts.any { it.enrichmentFailed }) {
            degraded += DegradationMarker(LayoutCapability.NAME, DegradationCodes.COMPOSE_ENRICHMENT_FAILED)
        }
        return MainResult(screenProvider(), windows, degraded, LevelResolution.delivered(level, hosts))
    }

    /** One Compose host at the requested level, or lower when tooling cannot
     * serve it (LevelResolution states every reduction on the host root). */
    private fun captureComposeHost(
        hostId: Long,
        hostView: View,
        tier2: Boolean,
        level: DetailLevel,
    ): HostResolution? {
        if (!presence.composePresent) return null
        val host = SemanticsCapture().capture(hostView) ?: return null
        val semantic = ComposeWalkResult(hostId, host.tree, host.embeddedHosts)
        val tooling = if (tier2) runCatching { enrich(hostId, hostView, host, level) } else null
        return LevelResolution.resolveHost(level, semantic, tooling)
    }

    /** Throws on any tooling failure (the caller discards everything). */
    private fun enrich(hostId: Long, hostView: View, host: SemanticsCapture.HostCapture, level: DetailLevel): ToolingOutcome {
        val read = ToolingEnrichment().read(hostView)
        val labels = ToolingTree.label(read.groups)
        if (labels.isEmpty()) error("tooling produced no groups")
        val labeled = EnrichmentOverlay.applyOrNull(host.tree, labels)!!
        val sourceInfo = ToolingTree.hasSourceInfo(read.groups)
        if (level == DetailLevel.SEMANTIC) {
            return ToolingOutcome(ComposeWalkResult(hostId, labeled, host.embeddedHosts), DetailLevel.SEMANTIC, sourceInfo)
        }
        val records = LayoutRecords.from(read.layoutNodes)
        val tree = ContainerAssembly.insertContainers(labeled, records, labels)
        val semanticEmbeds = host.embeddedHosts.map { it.composeNodeId }.toSet()
        val containerEmbeds = records.mapNotNull { r ->
            r.interopViewId?.takeIf { r.id !in semanticEmbeds }?.let { EmbeddedHost(r.id, it) }
        }
        return ToolingOutcome(ComposeWalkResult(hostId, tree, host.embeddedHosts + containerEmbeds), DetailLevel.LAYOUT, sourceInfo)
    }

    private fun <T> runOnMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        mainHandler.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw LayoutCaptureTimeout("layout capture exceeded ${timeoutMs}ms on the main thread")
        }
        return result!!.getOrThrow()
    }
}

class LayoutCaptureTimeout(message: String) : Exception(message)
