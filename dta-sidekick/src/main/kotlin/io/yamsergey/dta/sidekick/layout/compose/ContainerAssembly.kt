package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Provenance
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/**
 * `layout` level assembly (feature 003 US1): one Compose host's semantics tree
 * plus its tooling-discovered layout nodes, arranged by the ACTUAL layout
 * hierarchy (LayoutRecord.parentId, research R2). Layout nodes absent from
 * semantics become container ComposeNodes (provenance tooling). Pure.
 */
object ContainerAssembly {

    fun insertContainers(
        semanticRoot: Node,
        records: List<LayoutRecord>,
        labels: Map<Long, EnrichmentEntry>,
        maxDepth: Int = 50,
    ): Node {
        val semantic = LinkedHashMap<Long, Node>()
        val semanticParent = HashMap<Long, Long>()
        fun index(node: Node, parent: Long?) {
            val id = node.id ?: return
            semantic.putIfAbsent(id, node)
            parent?.let { semanticParent.putIfAbsent(id, it) }
            node.children.forEach { index(it, id) }
        }
        index(semanticRoot, null)
        val rootId = semanticRoot.id ?: return semanticRoot

        val recordById = LinkedHashMap<Long, LayoutRecord>()
        records.forEach { recordById.putIfAbsent(it.id, it) }
        val all = LinkedHashSet<Long>().apply { addAll(recordById.keys); addAll(semantic.keys) }

        // Sibling order: layout record order, then semantic-only nodes in
        // semantics order.
        val order = HashMap<Long, Int>()
        all.forEachIndexed { i, id -> order[id] = i }

        val children = HashMap<Long, MutableList<Long>>()
        for (id in all) {
            if (id == rootId) continue
            val record = recordById[id]
            val parent = when {
                record != null -> record.parentId?.takeIf { it in all && it != id } ?: rootId
                else -> semanticParent[id] ?: rootId
            }
            children.getOrPut(parent) { mutableListOf() } += id
        }
        children.values.forEach { list -> list.sortBy { order[it] } }

        val visited = HashSet<Long>()
        fun build(id: Long, depth: Int): Node {
            visited += id
            val capped = depth >= maxDepth
            val kids = if (capped) {
                emptyList()
            } else {
                children[id].orEmpty().filter { it !in visited }.map { build(it, depth + 1) }
            }
            val capMarker = if (capped && children[id].orEmpty().isNotEmpty()) {
                listOf(DegradationMarker("layout", DegradationCodes.DEPTH_CAP_REACHED))
            } else {
                emptyList()
            }
            val node = semantic[id]?.let { it.withChildren(kids) } ?: container(id, recordById.getValue(id), labels[id], kids)
            return if (capMarker.isEmpty()) node else node.withDegraded(capMarker)
        }
        return build(rootId, 0)
    }

    private fun container(id: Long, record: LayoutRecord, label: EnrichmentEntry?, children: List<Node>) =
        ComposeNode(
            id = id,
            bounds = record.bounds,
            name = label?.name,
            sourceFile = label?.sourceFile,
            lineNumber = label?.lineNumber,
            library = label?.library,
            provenance = Provenance.TOOLING,
            visible = if (!record.placed || !record.onScreen || record.bounds.area == 0) false else null,
            children = children,
        )

    private fun Node.withChildren(newChildren: List<Node>): Node = when (this) {
        is ComposeNode -> copy(children = newChildren)
        is io.yamsergey.dta.protocol.layout.ViewNode -> copy(children = newChildren)
        is io.yamsergey.dta.protocol.layout.FallbackNode -> copy(children = newChildren)
    }

    private fun Node.withDegraded(extra: List<DegradationMarker>): Node {
        val merged = (degraded.orEmpty() + extra).distinct()
        return when (this) {
            is ComposeNode -> copy(degraded = merged)
            is io.yamsergey.dta.protocol.layout.ViewNode -> copy(degraded = merged)
            is io.yamsergey.dta.protocol.layout.FallbackNode -> copy(degraded = merged)
        }
    }
}
