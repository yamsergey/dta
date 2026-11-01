package io.yamsergey.dta.protocol.layout

/**
 * A composable call's parameters once per capture (feature 018, research R2): the first node of a
 * call in tree order carries them; the call's other nodes name it and point to that node. Below
 * the composition level, parameters the caller did not pass are left out and counted. Runs last,
 * after every step that adds nodes. Pure.
 */
object CallParameters {
    fun finish(root: Node, keepDefaults: Boolean): Node {
        val params = HashMap<Int, List<Parameter>>()
        fun gather(n: Node) {
            if (n is ComposeNode) n.callKey?.let { k -> n.parameters?.let { params.putIfAbsent(k, it) } }
            n.children.forEach(::gather)
        }
        gather(root)

        fun shown(all: List<Parameter>?): Pair<List<Parameter>?, Int?> {
            all ?: return null to null
            if (keepDefaults) return all.ifEmpty { null } to null
            val kept = all.filterNot { it.default }
            return kept.ifEmpty { null } to (all.size - kept.size).takeIf { it > 0 }
        }

        // Pre-order: a node takes or points to its call's owner before its children are visited.
        val owner = HashMap<Int, Long?>()
        fun visit(n: Node): Node {
            if (n !is ComposeNode) return n.withChildren(n.children.map(::visit))
            val k = n.callKey
            val self = when {
                k == null -> shown(n.parameters).let { (p, d) -> n.copy(parameters = p, defaultParameters = d) }
                k !in owner -> {
                    owner[k] = n.id
                    shown(params[k]).let { (p, d) -> n.copy(parameters = p, defaultParameters = d, sameCallAs = null) }
                }
                else -> n.copy(parameters = null, defaultParameters = null, sameCallAs = owner[k])
            }
            return self.copy(children = n.children.map(::visit))
        }
        return visit(root)
    }

    private fun Node.withChildren(newChildren: List<Node>): Node = when (this) {
        is ViewNode -> copy(children = newChildren)
        is ComposeNode -> copy(children = newChildren)
        is FallbackNode -> copy(children = newChildren)
    }
}
