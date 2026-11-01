package io.yamsergey.dta.sidekick.input

/** The composable a click landed on (feature 016 US7, prototype findings a): pure, over a semantics tree. */
object ComposeClicks {
    data class Node(
        val id: Int,
        val testTag: String? = null,
        val text: String? = null,
        val role: String? = null,
        val children: List<Node> = emptyList(),
    )

    fun target(root: Node, id: Int, secure: Boolean, windowId: String?): InputEvents.Target? {
        val node = find(root, id) ?: return null
        return InputEvents.Target(
            name = node.testTag,
            className = node.role ?: "Composable",
            text = node.text,
            role = "button",
            secure = secure,
            windowId = windowId,
        )
    }

    private fun find(n: Node, id: Int): Node? = if (n.id == id) n else n.children.firstNotNullOfOrNull { find(it, id) }
}
