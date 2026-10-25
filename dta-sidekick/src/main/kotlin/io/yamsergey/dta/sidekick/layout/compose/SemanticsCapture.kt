package io.yamsergey.dta.sidekick.layout.compose

import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.Interaction
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.sidekick.layout.EmbeddedHost

/**
 * Tier 1 Compose capture (research D1): the always-available semantics tree.
 * Uses the PUBLIC SemanticsNode/SemanticsProperties API — version-robust,
 * single-digit-ms (the industry-consensus path). AndroidComposeView is
 * internal, so its semanticsOwner is reached by reflection (in-app, safe).
 * Loaded ONLY behind ComposePresence, so Views-only apps never touch it.
 */
class SemanticsCapture(private val maxDepth: Int = 50) {

    class HostCapture(val tree: Node, val embeddedHosts: List<EmbeddedHost>)

    /** Null when [host] is not an AndroidComposeView or has no semantics owner. */
    fun capture(host: View): HostCapture? {
        val owner = semanticsOwnerOf(host) ?: return null
        val root = owner.unmergedRootSemanticsNode
        val embedded = mutableListOf<EmbeddedHost>()
        return HostCapture(walk(root, 0, embedded), embedded)
    }

    private fun semanticsOwnerOf(host: View): SemanticsOwner? = try {
        if (host::class.java.name != COMPOSE_VIEW) {
            null
        } else {
            val method = host.javaClass.methods.first { it.name == "getSemanticsOwner" }
            method.invoke(host) as? SemanticsOwner
        }
    } catch (_: Throwable) {
        null
    }

    private fun walk(node: SemanticsNode, depth: Int, embedded: MutableList<EmbeddedHost>): Node {
        val config = node.config
        val b = node.boundsInWindow
        val interactions = buildList {
            if (config.getOrNull(SemanticsActions.OnClick) != null) add(Interaction.CLICKABLE)
            if (config.getOrNull(SemanticsActions.OnLongClick) != null) add(Interaction.LONG_CLICKABLE)
            if (config.getOrNull(SemanticsActions.ScrollBy) != null) add(Interaction.SCROLLABLE)
            if (config.getOrNull(SemanticsProperties.Focused) != null) add(Interaction.FOCUSABLE)
        }
        embeddedViewDrawingId(node)?.let { embedded += EmbeddedHost(node.id.toLong(), it) }

        val capped = depth >= maxDepth
        val children = if (capped) emptyList() else node.children.map { walk(it, depth + 1, embedded) }

        return ComposeNode(
            id = node.id.toLong(),
            bounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()),
            role = config.getOrNull(SemanticsProperties.Role)?.toString(),
            testTag = config.getOrNull(SemanticsProperties.TestTag),
            text = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.ifEmpty { null }
                ?: config.getOrNull(SemanticsProperties.EditableText)?.text?.ifEmpty { null },
            contentDescription = config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(", ")?.ifEmpty { null },
            interactions = interactions,
            enabled = config.getOrNull(SemanticsProperties.Disabled) == null,
            children = children,
            degraded = if (capped) listOf(DegradationMarker("layout", DegradationCodes.DEPTH_CAP_REACHED)) else null,
        )
    }

    private fun embeddedViewDrawingId(node: SemanticsNode): Long? =
        LayoutNodeProbes.interopViewDrawingId(node.layoutInfo)

    companion object {
        const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
    }
}
