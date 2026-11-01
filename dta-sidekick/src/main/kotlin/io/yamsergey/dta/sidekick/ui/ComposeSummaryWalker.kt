package io.yamsergey.dta.sidekick.ui

import android.view.View
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.lang.reflect.Method

/**
 * The composables inside a Compose host as [RawNode]s, from the public semantics tree
 * (feature 015, research R5). Loaded only when Compose is present (as [io.yamsergey.dta
 * .sidekick.layout.compose.SemanticsCapture] is), so Views-only apps never link it.
 */
class ComposeSummaryWalker {
    private var getter: Method? = null

    fun isHost(view: View): Boolean = view.javaClass.name == COMPOSE_VIEW

    /** The host's semantics children and how many nodes were visited, or null without an owner. */
    fun walk(host: View, budget: Int): Pair<List<RawNode>, Int>? {
        val owner = ownerOf(host) ?: return null
        var visited = 0
        fun node(n: SemanticsNode): RawNode {
            visited++
            val config = n.config
            val children = ArrayList<RawNode>()
            for (child in n.children) {
                if (visited >= budget) break
                children += node(child)
            }
            val scrollable = config.getOrNull(SemanticsActions.ScrollBy) != null
            return RawNode(
                className = config.getOrNull(SemanticsProperties.Role)?.toString() ?: "Node",
                text = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.ifEmpty { null }
                    ?: config.getOrNull(SemanticsProperties.EditableText)?.text?.ifEmpty { null },
                description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ")?.ifEmpty { null },
                name = config.getOrNull(SemanticsProperties.TestTag),
                clickable = config.getOrNull(SemanticsActions.OnClick) != null && config.getOrNull(SemanticsProperties.EditableText) == null,
                editable = config.getOrNull(SemanticsProperties.EditableText) != null,
                heading = config.getOrNull(SemanticsProperties.Heading) != null,
                checkable = config.getOrNull(SemanticsProperties.ToggleableState) != null,
                checked = config.getOrNull(SemanticsProperties.ToggleableState)?.let { it.name == "On" },
                password = config.getOrNull(SemanticsProperties.Password) != null,
                progress = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null,
                enabled = config.getOrNull(SemanticsProperties.Disabled) == null,
                visible = config.getOrNull(SemanticsProperties.InvisibleToUser) == null,
                scroll = if (scrollable) scrollOf(n) else null,
                children = children,
            )
        }
        val root = owner.unmergedRootSemanticsNode
        return root.children.map { node(it) } to visited
    }

    private fun scrollOf(n: SemanticsNode): String {
        val v = n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f
        val h = n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke() ?: 0f
        val first = n.children.firstOrNull()?.boundsInRoot
        return "$h,$v,${first?.left ?: 0f},${first?.top ?: 0f}"
    }

    private fun ownerOf(host: View): SemanticsOwner? = try {
        val m = getter ?: host.javaClass.methods.first { it.name == "getSemanticsOwner" }.also { getter = it }
        m.invoke(host) as? SemanticsOwner
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
    }
}
