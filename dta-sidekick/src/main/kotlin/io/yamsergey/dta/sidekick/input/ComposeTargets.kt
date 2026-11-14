package io.yamsergey.dta.sidekick.input

import android.view.View
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.yamsergey.dta.shim.ShimReflect

/**
 * The element a Compose modifier node acts on (a clickable's or a scrollable's): its layout
 * node, the host view, and the element as the timeline names it (merged semantics: key, role,
 * text). Shared by clicks (016) and scrolls (064).
 */
internal object ComposeTargets {
    class Found(val owner: View, val layoutNode: Any, val target: InputEvents.Target)

    fun of(modifierNode: Any, windowIdOf: (View) -> String?, fallbackClass: String, fallbackRole: String?): Found? {
        val coordinator = ShimReflect.field(modifierNode, "coordinator") ?: return null
        val layoutNode = coordinator.javaClass.getMethod("getLayoutNode").invoke(coordinator) ?: return null
        val id = layoutNode.javaClass.getMethod("getSemanticsId").invoke(layoutNode) as Int
        val owner = ShimReflect.field(layoutNode, "owner") as? View ?: return null
        val semantics = io.yamsergey.dta.sidekick.util.NoArgMethods.call(owner, "getSemanticsOwner") as? SemanticsOwner ?: return null
        val window = runCatching { owner.rootView.layoutParams as? WindowManager.LayoutParams }.getOrNull()
        val secure = window != null && (window.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        val target = ComposeClicks.target(tree(semantics.rootSemanticsNode), id, secure, windowIdOf(owner.rootView))
            ?: InputEvents.Target(null, fallbackClass, null, fallbackRole, secure, windowIdOf(owner.rootView))
        return Found(owner, layoutNode, target)
    }

    private fun tree(n: SemanticsNode): ComposeClicks.Node = ComposeClicks.Node(
        id = n.id,
        testTag = n.config.getOrNull(SemanticsProperties.TestTag),
        text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.ifEmpty { null }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", "),
        role = n.config.getOrNull(SemanticsProperties.Role)?.toString(),
        children = n.children.map(::tree),
    )
}
