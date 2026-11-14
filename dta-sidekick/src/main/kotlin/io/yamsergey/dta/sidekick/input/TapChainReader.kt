package io.yamsergey.dta.sidekick.input

import android.view.View
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
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
            // One walk finds the node and every layout ancestor (the owner's root, without a
            // parent, is in no table): a subcomposition's host is the nearest ancestor in another table.
            val ancestors = TapPath.ancestors(node).filter { it.parentInfo != null }.toList()
            val where = TapPath.findAll(tables, listOf<Any>(node) + ancestors)
            val found = where[node] ?: return InputEvents.TapChain(null, false, "node-not-found", ms())
            var partial = false
            fun chainOf(at: TapPath.Found, nodeId: Long): List<Frame>? {
                val mapped = enrichment.map(TapPath.pruned(tables[at.table], at.steps)) ?: return null
                val own = ToolingTree.chains(listOf(mapped), emptyList(), false).chains[nodeId] ?: return null
                val top = at.path.firstNotNullOfOrNull { it.node as? LayoutInfo } ?: return own
                val above = TapPath.ancestors(top).filter { it.parentInfo != null }.toList()
                for (a in above) {
                    val host = where[a]?.takeIf { it.table != at.table } ?: continue
                    val hostId = LayoutNodeProbes.semanticsId(a) ?: break
                    return (chainOf(host, hostId) ?: break) + own
                }
                if (above.isNotEmpty()) partial = true
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
            // The whole read path once over a one-group table: proxies, mapTree, source-info
            // parsing, the classifier and the chain builder are loaded before the first tap.
            val group = fake(CompositionGroup::class.java, "C(DtaWarmUp):DtaWarmUp.kt#0")
            val table = fake(CompositionData::class.java, null, listOf(group))
            val mapped = ToolingEnrichment(CallClassifier(null, context.packageName)).map(TapPath.pruned(table, listOf(0)))
            TapPath.find(listOf(table), Any())
            ToolingTree.chains(listOfNotNull(mapped), emptyList(), false)
            walkRealTables()
        } catch (_: Throwable) {
            // The first tap then pays for it; its read time says so.
        }
    }

    /**
     * The slot-table walk is most of a cold tap (25 of 36 ms, measured): walking the real tables
     * once here, off the main thread, compiles it. The walk only reads (group iteration opens no
     * reader or writer, and a concurrent edit throws on this thread, never on the app's); the
     * tables are taken on the main thread. Waits up to 30 s for the first Compose screen.
     */
    private fun walkRealTables() {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        repeat(30) {
            Thread.sleep(1_000)
            val tables = ArrayList<CompositionData>()
            val taken = java.util.concurrent.CountDownLatch(1)
            main.post {
                try {
                    val enrichment = ToolingEnrichment()
                    fun hosts(v: View): List<View> = if (v.javaClass.name == COMPOSE_VIEW) listOf(v)
                        else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { hosts(g.getChildAt(it)) } }.orEmpty()
                    io.yamsergey.dta.sidekick.layout.ViewCapture().discoverRoots().flatMap(::hosts)
                        .forEach { h -> enrichment.compositionDataSet(h)?.forEach { (it as? CompositionData)?.let(tables::add) } }
                } catch (_: Throwable) {
                } finally {
                    taken.countDown()
                }
            }
            if (!taken.await(2, java.util.concurrent.TimeUnit.SECONDS) || tables.isEmpty()) return@repeat
            repeat(3) { runCatching { TapPath.find(tables, Any()) } }
            return
        }
    }

    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"

    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, sourceInfo: String?, children: List<CompositionGroup> = emptyList()): T =
        java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, m, _ ->
            when (m.name) {
                "getCompositionGroups" -> children
                "getSourceInfo" -> sourceInfo
                "getKey" -> 0
                "getData" -> emptyList<Any?>()
                "isEmpty" -> children.isEmpty()
                "hashCode" -> System.identityHashCode(self)
                "equals" -> false
                "toString" -> "DtaWarmUp"
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as T

    private fun classifier(owner: View): CallClassifier {
        val index = (PackageIndexHolder.of(owner.context).await(0) as? PackageIndexHolder.State.Ready)?.index
        return CallClassifier(index, owner.context.packageName)
    }
}
