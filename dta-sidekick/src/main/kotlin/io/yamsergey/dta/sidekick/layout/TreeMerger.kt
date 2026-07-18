package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.FallbackNode
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/** A classic View embedded inside Compose (AndroidView) — moved under its
 * Compose parent node identified by [composeNodeId], matched by identity. */
data class EmbeddedHost(val composeNodeId: Long, val viewDrawingId: Long)

/**
 * View walk output. Compose-host positions are recorded out-of-band by drawing
 * id ([composeHostIds]) rather than as a parallel node type, so the whole
 * intermediate is plain wire [Node]s — one model (constitution VI).
 */
data class ViewWalkResult(
    val root: Node,
    val embeddedViewRoots: Map<Long, Node> = emptyMap(),
    val composeHostIds: Set<Long> = emptySet(),
)

data class ComposeWalkResult(
    val hostDrawingId: Long,
    val tree: Node,
    val embeddedHostDrawingIds: List<EmbeddedHost> = emptyList(),
)

/**
 * Pure merge: attach each compose tree under its host view node and reparent
 * embedded Views under their Compose parent BY IDENTITY (drawing id), never by
 * ordinal position (fixes prototype fragility). No Android imports.
 */
object TreeMerger {

    fun merge(viewWalk: ViewWalkResult, composeWalks: List<ComposeWalkResult>): Node {
        val byHost = composeWalks.associateBy { it.hostDrawingId }
        return spliceViewNode(viewWalk.root, viewWalk.composeHostIds, byHost, viewWalk.embeddedViewRoots)
    }

    private fun spliceViewNode(
        node: Node,
        hostIds: Set<Long>,
        byHost: Map<Long, ComposeWalkResult>,
        embeddedViewRoots: Map<Long, Node>,
    ): Node {
        if (node.id != null && node.id in hostIds) {
            val walk = byHost[node.id]
            val composeChild = if (walk == null) {
                firstFramePending(node)
            } else {
                embedViews(walk.tree, walk.embeddedHostDrawingIds, embeddedViewRoots)
            }
            return node.withChildren(listOf(composeChild))
        }
        val newChildren = node.children.map { spliceViewNode(it, hostIds, byHost, embeddedViewRoots) }
        return node.withChildren(newChildren)
    }

    /** Walk the compose tree; wherever a node's id is a known embedded-host,
     * attach the corresponding embedded view subtree — matched by id. */
    private fun embedViews(
        composeTree: Node,
        embeds: List<EmbeddedHost>,
        embeddedViewRoots: Map<Long, Node>,
    ): Node {
        if (embeds.isEmpty()) return composeTree
        val byComposeNode = embeds.associate { it.composeNodeId to it.viewDrawingId }
        fun rec(node: Node): Node {
            val mappedChildren = node.children.map { rec(it) }
            val embeddedViewId = byComposeNode[node.id]
            val extra = embeddedViewId?.let { embeddedViewRoots[it] }
            val children = if (extra != null) mappedChildren + extra else mappedChildren
            return node.withChildren(children)
        }
        return rec(composeTree)
    }

    private fun firstFramePending(host: Node): Node =
        ComposeNode(
            id = host.id,
            bounds = host.bounds,
            name = null,
            degraded = listOf(DegradationMarker("layout", DegradationCodes.COMPOSE_FIRST_FRAME_PENDING)),
        )

    private fun Node.withChildren(newChildren: List<Node>): Node = when (this) {
        is ViewNode -> copy(children = newChildren)
        is ComposeNode -> copy(children = newChildren)
        is FallbackNode -> copy(children = newChildren)
    }
}
