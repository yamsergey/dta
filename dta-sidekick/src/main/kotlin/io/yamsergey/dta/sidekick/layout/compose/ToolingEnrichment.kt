package io.yamsergey.dta.sidekick.layout.compose

import android.view.View
import io.yamsergey.dta.protocol.layout.Parameter

/**
 * Tier 2 Compose enrichment (research D1/D2): composable names + source
 * location via ui-tooling-data's `asTree()`. Reached entirely by REFLECTION —
 * not compile-time tooling types — so the version-coupled surface can't break
 * compilation, and any runtime shape difference throws → the caller degrades
 * to Tier 1 (`compose-enrichment-failed`, all-or-none). Loaded only behind
 * ComposePresence's Tier-2 gate.
 *
 * The join key to the Tier-1 semantics tree is the layout node's semanticsId.
 */
class ToolingEnrichment {

    /** Builds the overlay keyed by semantics id, or throws (caller wraps in
     * EnrichmentOverlay.buildOrNull for all-or-none). */
    fun buildOverlay(host: View): Map<Long, EnrichmentEntry> {
        val tables = compositionDataSet(host) ?: error("no CompositionData on host")
        val asTree = Class.forName("androidx.compose.ui.tooling.data.SlotTreeKt")
            .methods.first { it.name == "asTree" }

        val out = HashMap<Long, EnrichmentEntry>()
        for (data in tables) {
            val group = asTree.invoke(null, data) ?: continue
            collect(group, out)
        }
        if (out.isEmpty()) error("tooling produced no groups")
        return out
    }

    private fun collect(group: Any, out: MutableMap<Long, EnrichmentEntry>) {
        val name = group.call("getName") as? String
        val semanticsId = semanticsIdOf(group)
        val location = group.call("getLocation")
        val sourceFile = location?.call("getSourceFile") as? String
        val line = (location?.call("getLineNumber") as? Int)?.takeIf { it > 0 }

        if (name != null && semanticsId != null) {
            out[semanticsId] = EnrichmentEntry(
                name = normalize(name),
                sourceFile = sourceFile,
                lineNumber = line,
                parameters = parametersOf(group),
                library = sourceFile == null,
            )
        }
        @Suppress("UNCHECKED_CAST")
        (group.call("getChildren") as? Iterable<Any>)?.forEach { collect(it, out) }
    }

    private fun semanticsIdOf(group: Any): Long? = try {
        val node = group.call("getNode")
        (node?.call("getSemanticsId") as? Int)?.toLong()
    } catch (_: Throwable) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parametersOf(group: Any): List<Parameter> = try {
        (group.call("getParameters") as? Iterable<Any>)?.mapNotNull { p ->
            val n = p.call("getName") as? String ?: return@mapNotNull null
            val v = p.call("getValue")
            Parameter(n, v?.toString() ?: "null", v?.let { it::class.simpleName } ?: "null")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun compositionDataSet(host: View): Iterable<Any>? {
        val tagId = host.resources.getIdentifier("inspection_slot_table_set", "id", "androidx.compose.ui")
        if (tagId == 0) return null
        return host.getTag(tagId) as? Iterable<Any>
    }

    private fun Any.call(method: String): Any? =
        javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }?.invoke(this)

    private fun normalize(name: String): String = when (name) {
        "BasicText" -> "Text"
        "BasicTextField" -> "TextField"
        else -> name
    }
}
