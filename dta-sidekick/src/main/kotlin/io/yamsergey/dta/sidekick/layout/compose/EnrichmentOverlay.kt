package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Parameter

/** Tier-2 data for one composable, keyed by semantics id. */
data class EnrichmentEntry(
    val name: String,
    val sourceFile: String?,
    val lineNumber: Int?,
    val parameters: List<Parameter>,
    val library: Boolean,
)

/**
 * Overlays Tier-2 enrichment onto a Tier-1 semantics tree by semantics id.
 * All-or-none (FR-005) is enforced by the caller: the whole tooling read runs
 * guarded, and LevelResolution discards it on any failure.
 */
object EnrichmentOverlay {

    /** Applies [overlay] onto [tree] by node id. Returns null iff overlay is
     * null (nothing to apply — caller keeps the Tier-1 tree + degradation). */
    fun applyOrNull(tree: Node, overlay: Map<Long, EnrichmentEntry>?): Node? {
        if (overlay == null) return null
        fun rec(node: Node): Node {
            val mapped = node.children.map { rec(it) }
            if (node !is ComposeNode) return node.withChildren(mapped)
            val entry = node.id?.let { overlay[it] }
            return if (entry == null) {
                node.copy(children = mapped)
            } else {
                node.copy(
                    name = entry.name,
                    sourceFile = entry.sourceFile,
                    lineNumber = entry.lineNumber,
                    parameters = entry.parameters.ifEmpty { null },
                    library = entry.library,
                    children = mapped,
                )
            }
        }
        return rec(tree)
    }

    private fun Node.withChildren(newChildren: List<Node>): Node = when (this) {
        is io.yamsergey.dta.protocol.layout.ViewNode -> copy(children = newChildren)
        is ComposeNode -> copy(children = newChildren)
        is io.yamsergey.dta.protocol.layout.FallbackNode -> copy(children = newChildren)
    }
}
