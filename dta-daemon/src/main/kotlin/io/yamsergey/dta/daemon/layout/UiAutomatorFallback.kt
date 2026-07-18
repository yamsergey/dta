package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.protocol.layout.FallbackNode
import io.yamsergey.dta.protocol.layout.Interaction
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Rect

/**
 * Fallback capture from `uiautomator dump`. Dump exec is a thin AdbExec
 * adapter; parse/prune/fuse is pure (constitution IV), porting the prototype's
 * rules so agent ergonomics carry over — but always labeled by the caller.
 */
class UiAutomatorFallback(private val runAdb: (List<String>) -> AdbExec.Result) {

    /** Runs `uiautomator dump` on the device and returns the pruned root. */
    fun capture(serial: String, screenWidth: Int, screenHeight: Int): Node {
        runAdb(listOf("-s", serial, "shell", "uiautomator", "dump", DUMP_PATH))
        val xml = runAdb(listOf("-s", serial, "shell", "cat", DUMP_PATH)).stdout
        return parseAndPrune(xml, screenWidth, screenHeight)
    }

    companion object {
        private const val DUMP_PATH = "/sdcard/dta_window_dump.xml"

        private val SYSTEM_BAR_IDS = setOf(
            "android:id/statusBarBackground",
            "android:id/navigationBarBackground",
            "com.android.systemui:id/status_bar",
            "com.android.systemui:id/navigation_bar_frame",
        )

        /** Raw parsed node before pruning — carries the flags pruning needs. */
        private class Raw(
            val className: String,
            val resourceId: String?,
            val text: String?,
            val contentDescription: String?,
            val bounds: Rect,
            val clickable: Boolean,
            val longClickable: Boolean,
            val scrollable: Boolean,
            val focusable: Boolean,
            val checkable: Boolean,
            val checked: Boolean,
            val selected: Boolean,
            val children: MutableList<Raw> = mutableListOf(),
        ) {
            val interesting: Boolean
                get() = !text.isNullOrEmpty() || !contentDescription.isNullOrEmpty() ||
                    clickable || longClickable || scrollable
        }

        fun parseAndPrune(xml: String, screenWidth: Int, screenHeight: Int): Node {
            val raw = parse(xml)
            val pruned = raw?.let { prune(it) } ?: emptyRoot(screenWidth, screenHeight)
            return toNode(pruned)
        }

        private fun emptyRoot(w: Int, h: Int) = Raw(
            "hierarchy", null, null, null, Rect(0, 0, w, h),
            clickable = false, longClickable = false, scrollable = false,
            focusable = false, checkable = false, checked = false, selected = false,
        )

        // --- Parse (lightweight tag scan; uiautomator XML is flat attributes) ---

        private val NODE_OPEN = Regex("""<node\s([^>]*?)(/?)>""")
        private val ATTR = Regex("""(\S+?)="([^"]*)"""")
        private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        private fun parse(xml: String): Raw? {
            val stack = ArrayDeque<Raw>()
            var root: Raw? = null
            var i = 0
            while (i < xml.length) {
                if (xml.startsWith("</node>", i)) {
                    stack.removeLastOrNull()
                    i += 7
                    continue
                }
                val m = NODE_OPEN.find(xml, i)
                if (m == null) break
                if (m.range.first != i) {
                    // skip non-node content up to the next node tag
                    i = m.range.first
                }
                val attrs = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                val b = BOUNDS.find(attrs["bounds"] ?: "")?.destructured
                val raw = Raw(
                    className = attrs["class"] ?: "android.view.View",
                    resourceId = attrs["resource-id"]?.ifEmpty { null },
                    text = attrs["text"]?.ifEmpty { null },
                    contentDescription = attrs["content-desc"]?.ifEmpty { null },
                    bounds = b?.let { (l, t, r, bo) -> Rect(l.toInt(), t.toInt(), r.toInt(), bo.toInt()) }
                        ?: Rect(0, 0, 0, 0),
                    clickable = attrs["clickable"] == "true",
                    longClickable = attrs["long-clickable"] == "true",
                    scrollable = attrs["scrollable"] == "true",
                    focusable = attrs["focusable"] == "true",
                    checkable = attrs["checkable"] == "true",
                    checked = attrs["checked"] == "true",
                    selected = attrs["selected"] == "true",
                )
                if (root == null) root = raw
                stack.lastOrNull()?.children?.add(raw)
                val selfClosing = m.groupValues[2] == "/"
                if (!selfClosing) stack.addLast(raw)
                i = m.range.last + 1
            }
            return root
        }

        // --- Prune / fuse (ported prototype rules) ---

        private fun prune(node: Raw): Raw? {
            // Drop zero-area and system decoration.
            if (node.bounds.area <= 0 && node.children.isEmpty()) return null
            if (node.resourceId in SYSTEM_BAR_IDS) return null

            val prunedChildren = node.children.mapNotNull { prune(it) }.toMutableList()

            // Clickable-label fusion: clickable unlabeled parent + single labeled
            // non-clickable leaf → pull the label up, drop the child.
            if ((node.clickable || node.longClickable) && node.text.isNullOrEmpty() &&
                prunedChildren.size == 1
            ) {
                val child = prunedChildren.single()
                if (!child.text.isNullOrEmpty() && !child.clickable && child.children.isEmpty()) {
                    node.children.clear()
                    return Raw(
                        node.className, node.resourceId, child.text, node.contentDescription,
                        node.bounds, node.clickable, node.longClickable, node.scrollable,
                        node.focusable, node.checkable, node.checked, node.selected,
                    )
                }
            }

            node.children.clear()
            node.children.addAll(prunedChildren)

            // Collapse uninteresting single-child wrapper → promote the child.
            if (!node.interesting && node.children.size == 1) {
                return node.children.single()
            }
            // Keep interesting nodes, or structural multi-child nodes.
            return node
        }

        private fun toNode(raw: Raw): Node {
            val interactions = buildList {
                if (raw.clickable) add(Interaction.CLICKABLE)
                if (raw.longClickable) add(Interaction.LONG_CLICKABLE)
                if (raw.scrollable) add(Interaction.SCROLLABLE)
                if (raw.focusable) add(Interaction.FOCUSABLE)
            }
            return FallbackNode(
                bounds = raw.bounds,
                className = raw.className.substringAfterLast('.'),
                resourceId = raw.resourceId,
                text = raw.text,
                contentDescription = raw.contentDescription,
                interactions = interactions,
                checked = if (raw.checkable) raw.checked else null,
                selected = if (raw.selected) true else null,
                children = raw.children.map { toNode(it) },
            )
        }
    }
}
