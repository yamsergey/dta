package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.SummaryElement

/**
 * The screen's structure without its data (feature 016, research R7): the elements' roles and
 * names by depth, without texts or values; runs of siblings with the same structure count once
 * (a list of 2 and of 3 is the same screen); progress is left out. `Owner·xxxxxxxx`.
 */
object ScreenFingerprint {
    data class Fingerprint(val value: String, val truncated: Boolean)

    private class Node(val label: String, val depth: Int) {
        val children = ArrayList<Node>()
    }

    fun of(owner: String?, summary: ContentSummary): Fingerprint {
        val root = Node("", -1)
        val stack = ArrayDeque<Node>().apply { addLast(root) }
        for (e in summary.elements) {
            if (e.role == "progress") continue
            while (stack.last().depth >= e.depth) stack.removeLast()
            val node = Node(label(e), e.depth)
            stack.last().children += node
            stack.addLast(node)
        }
        var hash = 0x811c9dc5.toInt()
        for (b in signature(root).toByteArray()) hash = (hash xor (b.toInt() and 0xff)) * 0x01000193
        val name = owner?.substringAfterLast('.') ?: "screen"
        return Fingerprint("$name·${Integer.toHexString(hash).padStart(8, '0')}", summary.omitted > 0)
    }

    private fun label(e: SummaryElement) = "${e.role}:${e.name?.substringAfterLast('/') ?: ""}"

    private fun signature(node: Node): String {
        val children = node.children.map(::signature)
        val collapsed = children.filterIndexed { i, s -> i == 0 || s != children[i - 1] }
        return node.label + if (collapsed.isEmpty()) "" else collapsed.joinToString(",", "(", ")")
    }
}
