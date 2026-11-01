package io.yamsergey.dta.sidekick.ui

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Checkable
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Turns a window's Views (and, through [compose], the composables inside them) into [RawNode]s
 * for the summary (feature 015, research R5). Visits at most [maxNodes] nodes, so a huge window
 * costs a bounded walk; what it did not reach is beyond the summary's bound anyway.
 */
class WindowWalker(
    private val compose: ComposeSummaryWalker?,
    private val maxNodes: Int = MAX_NODES,
) {
    private var visited = 0

    fun walk(root: View): RawNode {
        visited = 0
        return node(root)
    }

    private fun node(view: View): RawNode {
        visited++
        val children = ArrayList<RawNode>()
        compose?.takeIf { it.isHost(view) }?.let { walker ->
            walker.walk(view, maxNodes - visited)?.let { (nodes, count) ->
                visited += count
                children += nodes
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (visited >= maxNodes) break
                val child = view.getChildAt(i) ?: continue
                if (child.visibility != View.VISIBLE) continue
                children += node(child)
            }
        }
        val text = (view as? TextView)?.text?.toString()?.ifEmpty { null }
        return RawNode(
            className = view.javaClass.name,
            text = text,
            description = view.contentDescription?.toString()?.ifEmpty { null },
            name = resourceName(view),
            clickable = view.isClickable && view !is EditText,
            editable = view is EditText,
            heading = isHeading(view),
            checkable = view is Checkable,
            checked = (view as? Checkable)?.isChecked,
            password = (view as? TextView)?.let(::isPassword) ?: false,
            progress = view is ProgressBar,
            enabled = view.isEnabled,
            visible = view.visibility == View.VISIBLE,
            scroll = scrollOf(view),
            children = children,
        )
    }

    private fun resourceName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID || id ushr 24 == 0) return null
        return try {
            view.resources.getResourceName(id)
        } catch (_: Exception) {
            null
        }
    }

    private fun isHeading(view: View): Boolean =
        (android.os.Build.VERSION.SDK_INT >= 28 && view.isAccessibilityHeading) ||
            view.javaClass.name.endsWith("DialogTitle") ||
            resourceName(view)?.endsWith(":id/alertTitle") == true

    private fun isPassword(view: TextView): Boolean {
        if (view.transformationMethod is PasswordTransformationMethod) return true
        val type = view.inputType
        val variation = type and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** A scrolling container's position: its scroll offset and where its first child sits. */
    private fun scrollOf(view: View): String? {
        val scrolls = view is AbsListView || view.canScrollVertically(1) || view.canScrollVertically(-1) ||
            view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)
        if (!scrolls) return null
        val first = (view as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)
        val position = (view as? AbsListView)?.firstVisiblePosition ?: 0
        return "${view.scrollX},${view.scrollY},$position,${first?.left ?: 0},${first?.top ?: 0}"
    }

    companion object {
        const val MAX_NODES = 2_000
    }
}
