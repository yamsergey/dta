package io.yamsergey.dta.sidekick.input

import android.view.View
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.layout.LayoutInfo
import io.yamsergey.dta.protocol.layout.Frame
import io.yamsergey.dta.sidekick.layout.compose.CallClassifier
import io.yamsergey.dta.sidekick.layout.compose.LayoutNodeProbes
import io.yamsergey.dta.sidekick.layout.compose.PackageIndexHolder
import io.yamsergey.dta.sidekick.layout.compose.ToolingEnrichment
import io.yamsergey.dta.sidekick.layout.compose.ToolingTree

/**
 * Feature 064 (US1, US2): the pressed composable's chain, read synchronously at the tap from the
 * last composed frame (the slot tables the host keeps for inspection). Only the node's path is
 * mapped ([TapPath]), through the same factory and app/library rule as Layout (054/059).
 *
 * A subcomposition (a lazy list item) gets the chain above it from its host: the layout parents
 * of its first layout node are followed into the other tables. Never throws: a failure is a cut
 * with a reason.
 */
object TapChainReader {

    fun read(owner: View, layoutNode: Any): InputEvents.TapChain {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            val node = layoutNode as? LayoutInfo ?: return InputEvents.TapChain(null, false, "not-compose-host", ms())
            val enrichment = ToolingEnrichment(classifier(owner))
            @Suppress("UNCHECKED_CAST")
            val tables = (enrichment.compositionDataSet(owner)?.toList() as List<CompositionData>?)?.takeIf { it.isNotEmpty() }
                ?: return InputEvents.TapChain(null, false, "no-tooling-data", ms())
            val id = LayoutNodeProbes.semanticsId(node) ?: return InputEvents.TapChain(null, false, "node-not-found", ms())
            val found = TapPath.find(tables, node) ?: return InputEvents.TapChain(null, false, "node-not-found", ms())
            val root = io.yamsergey.dta.sidekick.util.NoArgMethods.call(owner, "getRoot")
            var partial = false
            fun chainOf(at: TapPath.Found, nodeId: Long): List<Frame>? {
                val mapped = enrichment.map(TapPath.pruned(tables[at.table], at.steps)) ?: return null
                val own = ToolingTree.chains(listOf(mapped), emptyList(), false).chains[nodeId] ?: return null
                val top = at.path.firstNotNullOfOrNull { it.node as? LayoutInfo } ?: return own
                // The owner's root layout node is in no slot table: reaching it is reaching the top.
                val ancestors = TapPath.ancestors(top).filter { it !== root }.toList()
                for (a in ancestors) {
                    val host = TapPath.find(tables, a, skip = at.table) ?: continue
                    val hostId = LayoutNodeProbes.semanticsId(a) ?: break
                    val above = chainOf(host, hostId) ?: break
                    return above + own
                }
                if (ancestors.isNotEmpty()) partial = true
                return own
            }
            val frames = chainOf(found, id) ?: return InputEvents.TapChain(null, false, "node-not-found", ms())
            InputEvents.TapChain(frames, partial, null, ms())
        } catch (t: Throwable) {
            InputEvents.TapChain(null, false, "error: ${t.javaClass.simpleName}", ms())
        }
    }

    /** Resolves the tooling handles and starts the package index ahead of the first tap (off the main thread). */
    fun warmUp(context: android.content.Context) {
        try {
            PackageIndexHolder.of(context)
            ToolingEnrichment().warmUp()
        } catch (_: Throwable) {
            // The first tap then pays for it; its read time says so.
        }
    }

    private fun classifier(owner: View): CallClassifier {
        val index = (PackageIndexHolder.of(owner.context).await(0) as? PackageIndexHolder.State.Ready)?.index
        return CallClassifier(index, owner.context.packageName)
    }
}
