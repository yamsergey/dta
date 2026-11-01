package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.SummaryElement

/**
 * One node of a window as the summary sees it, built by the View and Compose walkers
 * ([WindowWalker]) so the rules here run without a device.
 */
data class RawNode(
    val className: String,
    val text: String? = null,
    val description: String? = null,
    /** Resource name or test tag. */
    val name: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val heading: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean? = null,
    val password: Boolean = false,
    val progress: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /** A scroll container's position ("x,y" or a list's first item and offset); null otherwise. */
    val scroll: String? = null,
    val children: List<RawNode> = emptyList(),
)

/** A summary plus what the element diff (US4) needs beside it. */
data class Summarized(
    val summary: ContentSummary,
    /** The first heading's text (a dialog's title), unless texts are withheld. */
    val heading: String?,
    val firstText: String?,
    /** Scroll position per scroll container key. */
    val scrolls: Map<String, String>,
    /** For each element key, the keys of the scroll containers above it. */
    val scrolledBy: Map<String, List<String>>,
)

/** The bounded structure-and-text description of a window (feature 015, research R5). */
object ContentSummaries {
    const val MAX_ELEMENTS = 50
    const val MAX_TEXT = 200

    fun summarize(
        root: RawNode,
        secure: Boolean,
        settled: Boolean,
        takenMonoMs: Long,
        maxElements: Int = MAX_ELEMENTS,
        maxText: Int = MAX_TEXT,
    ): Summarized {
        val elements = ArrayList<SummaryElement>()
        val scrolls = LinkedHashMap<String, String>()
        val scrolledBy = HashMap<String, List<String>>()
        var omitted = 0
        var heading: String? = null
        var firstText: String? = null

        fun visit(node: RawNode, key: String, depth: Int, containers: List<String>) {
            if (!node.visible) return
            val role = roleOf(node)
            if (role != null) {
                if (elements.size >= maxElements) {
                    omitted++
                } else {
                    val text = if (secure || node.password) null else node.text?.let { cut(it, maxText) }
                    val description = if (secure) null else node.description?.let { cut(it, maxText) }
                    if (role == "heading" && heading == null) heading = text
                    if (firstText == null && text != null) firstText = text
                    elements += SummaryElement(
                        role = role, text = text, description = description, name = node.name, depth = depth,
                        enabled = node.enabled, checked = node.checked, password = node.password, key = key,
                    )
                    if (containers.isNotEmpty()) scrolledBy[key] = containers
                }
            }
            val inner = if (node.scroll != null) containers + key else containers
            if (node.scroll != null) scrolls[key] = node.scroll
            val seen = HashMap<String, Int>()
            for (child in node.children) {
                val segment = segment(child)
                val index = seen.merge(segment, 1, Int::plus)!! - 1
                visit(child, "$key/$segment#$index", depth + 1, inner)
            }
        }
        visit(root, segment(root), 0, emptyList())
        return Summarized(
            summary = ContentSummary(elements, omitted, textsWithheld = secure, settled = settled, takenMonoMs = takenMonoMs),
            heading = heading,
            firstText = firstText,
            scrolls = scrolls,
            scrolledBy = scrolledBy,
        )
    }

    private fun segment(node: RawNode) = node.name?.substringAfterLast('/') ?: node.className.substringAfterLast('.')

    private fun roleOf(node: RawNode): String? = when {
        node.editable -> "input"
        node.heading -> "heading"
        node.clickable || node.checkable -> "button"
        node.progress -> "progress"
        node.text != null -> "text"
        node.description != null -> "image"
        else -> null
    }

    private fun cut(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"
}
