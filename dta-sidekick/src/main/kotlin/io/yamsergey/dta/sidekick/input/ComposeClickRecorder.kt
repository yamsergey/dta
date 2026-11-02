package io.yamsergey.dta.sidekick.input

import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.shim.ShimReflect
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.timeline.HandlingScope
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * Clicks on composables (feature 016 US7, prototype findings a): ENTRY/EXIT hooks on the
 * foundation's `handleUpEvent`, which runs `onClick` synchronously when a press ends inside an
 * enabled node. The element comes from the merged semantics node of the clicked layout node.
 * A foundation version without these methods is stated, not guessed at. Never throws.
 */
class ComposeClickRecorder(
    private val buffer: TimelineBuffer,
    private val scope: HandlingScope,
    private val windowIdOf: (View) -> String? = { null },
) {
    /** Null when installed; otherwise why Compose clicks are not recorded. */
    var failure: String? = null
        private set

    /** Whether each entry opened a scope, so its exit closes only its own. */
    private val opened = ArrayList<Boolean>()

    fun install(loader: ClassLoader?) {
        val results = TARGETS.mapNotNull { (cls, descriptor) ->
            val c = runCatching { Class.forName(cls, false, loader) }.getOrNull() ?: return@mapNotNull null
            if (ShimReflect.descriptors(c, "handleUpEvent").none { it == descriptor }) return@mapNotNull null
            listOf(
                Hooks.entry(c, "handleUpEvent", descriptor, EntryHandler { self, _ -> entered(self) }),
                Hooks.exit(c, "handleUpEvent", descriptor, ExitHandler { r -> exited(); r }),
            )
        }.flatten()
        failure = when {
            results.isEmpty() -> "this Compose foundation version has no handleUpEvent for DTA to observe; a newer DTA may cover it"
            results.none { it is Hooks.Install.Installed } -> results.filterNot { it is Hooks.Install.Installed }.map { it.describe() }.distinct().joinToString("; ")
            else -> null
        }
    }

    private fun entered(node: Any?) {
        val ok = try {
            node != null && ShimReflect.field(node, "enabled") == true && click(node)
        } catch (_: Throwable) {
            false
        }
        opened += ok
    }

    private fun exited() {
        try {
            if (opened.removeLastOrNull() == true) scope.exit()
        } catch (_: Throwable) {
        }
    }

    private fun click(node: Any): Boolean {
        val coordinator = ShimReflect.field(node, "coordinator") ?: return false
        val layoutNode = coordinator.javaClass.getMethod("getLayoutNode").invoke(coordinator) ?: return false
        val id = layoutNode.javaClass.getMethod("getSemanticsId").invoke(layoutNode) as Int
        val owner = ShimReflect.field(layoutNode, "owner") as? View ?: return false
        val semantics = owner.javaClass.methods.first { it.name == "getSemanticsOwner" }.invoke(owner) as? SemanticsOwner ?: return false
        val window = runCatching { owner.rootView.layoutParams as? WindowManager.LayoutParams }.getOrNull()
        val secure = window != null && (window.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        val target = ComposeClicks.target(tree(semantics.rootSemanticsNode), id, secure, windowIdOf(owner.rootView))
            ?: InputEvents.Target(null, "Composable", null, "button", secure, windowIdOf(owner.rootView))
        val event = buffer.append { eid, seq -> InputEvents.click(eid, seq, SystemClock.uptimeMillis(), System.currentTimeMillis(), target, via = "compose") }
        scope.enter(event.id, Kinds.CLICK)
        return true
    }

    private fun tree(n: SemanticsNode): ComposeClicks.Node = ComposeClicks.Node(
        id = n.id,
        testTag = n.config.getOrNull(SemanticsProperties.TestTag),
        text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.ifEmpty { null }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", "),
        role = n.config.getOrNull(SemanticsProperties.Role)?.toString(),
        children = n.children.map(::tree),
    )

    private companion object {
        const val POINTER = "Landroidx/compose/ui/input/pointer/PointerInputChange;"
        const val INDIRECT = "Landroidx/compose/ui/input/indirect/IndirectPointerInputChange;"
        val TARGETS = listOf(
            "androidx.compose.foundation.ClickableNode" to "($POINTER)V",
            "androidx.compose.foundation.ClickableNode" to "($INDIRECT)V",
            "androidx.compose.foundation.CombinedClickableNode" to "(J$POINTER)V",
            "androidx.compose.foundation.CombinedClickableNode" to "(J$INDIRECT)V",
        )
    }
}
