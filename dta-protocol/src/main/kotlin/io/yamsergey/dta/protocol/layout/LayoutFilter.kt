package io.yamsergey.dta.protocol.layout

/**
 * On-device tree filter (shared so both sides and clients evaluate identically,
 * constitution VI). Set fields AND-combine (clarification #4); a match yields
 * the node plus its light ancestor chain. Pure — deviceless-tested.
 */
@kotlinx.serialization.Serializable
data class LayoutFilter(
    val text: String? = null,
    val type: String? = null,
    val resourceId: String? = null,
    val rootId: Long? = null,
) {
    val isEmpty: Boolean get() = text == null && type == null && resourceId == null && rootId == null

    fun apply(root: Node, windowIndex: Int): List<Match> {
        val out = mutableListOf<Match>()
        walk(root, ArrayDeque(), inScope = rootId == null, windowIndex, out)
        return withCallParameters(root, out)
    }

    /**
     * Feature 018: a match whose call's parameters are on a node that did not match takes them,
     * so filtering never loses a parameter; when that node matched too, the reference stays.
     */
    private fun withCallParameters(root: Node, matches: List<Match>): List<Match> {
        val pointing = matches.mapNotNull { (it.node as? ComposeNode)?.sameCallAs }.toSet()
        if (pointing.isEmpty()) return matches
        val matched = matches.mapNotNull { it.node.id }.toSet()
        val owners = HashMap<Long, ComposeNode>()
        fun find(n: Node) {
            if (n is ComposeNode && n.id in pointing) owners[n.id!!] = n
            n.children.forEach(::find)
        }
        find(root)
        return matches.map { m ->
            val node = m.node as? ComposeNode ?: return@map m
            val ownerId = node.sameCallAs ?: return@map m
            if (ownerId in matched) return@map m
            val owner = owners[ownerId] ?: return@map m
            m.copy(node = node.copy(parameters = owner.parameters, defaultParameters = owner.defaultParameters, sameCallAs = null))
        }
    }

    private fun walk(
        node: Node,
        ancestors: ArrayDeque<Node>,
        inScope: Boolean,
        windowIndex: Int,
        out: MutableList<Match>,
    ) {
        val nowInScope = inScope || (rootId != null && node.id == rootId)
        if (nowInScope && matches(node)) {
            out += Match(
                window = windowIndex,
                node = node.withoutChildren(),
                ancestors = ancestors.map { it.toRef() },
            )
        }
        ancestors.addLast(node)
        node.children.forEach { walk(it, ancestors, nowInScope, windowIndex, out) }
        ancestors.removeLast()
    }

    private fun matches(node: Node): Boolean {
        text?.let { q ->
            val hay = listOfNotNull(node.text, node.contentDescription, (node as? ViewNode)?.hint)
            if (hay.none { it.contains(q, ignoreCase = true) }) return false
        }
        type?.let { t ->
            val ok = when (node) {
                is ViewNode -> node.className.substringAfterLast('.') == t || node.className.endsWith(".$t")
                is ComposeNode -> node.name == t
                is FallbackNode -> node.className.substringAfterLast('.') == t
            }
            if (!ok) return false
        }
        resourceId?.let { r ->
            val rid = when (node) {
                is ViewNode -> node.resourceId
                is FallbackNode -> node.resourceId
                is ComposeNode -> null
            }
            if (rid != r) return false
        }
        return true
    }

    private fun Node.toRef() = AncestorRef(
        kind = when (this) { is ViewNode -> "view"; is ComposeNode -> "compose"; is FallbackNode -> "fallback" },
        label = when (this) {
            is ViewNode -> className.substringAfterLast('.')
            is ComposeNode -> name ?: role ?: "Composable"
            is FallbackNode -> className
        },
        id = id,
        bounds = bounds,
    )

    private fun Node.withoutChildren(): Node = when (this) {
        is ViewNode -> copy(children = emptyList())
        is ComposeNode -> copy(children = emptyList())
        is FallbackNode -> copy(children = emptyList())
    }
}
