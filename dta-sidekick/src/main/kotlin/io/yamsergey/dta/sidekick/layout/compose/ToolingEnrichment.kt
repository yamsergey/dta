package io.yamsergey.dta.sidekick.layout.compose

import android.view.View
import io.yamsergey.dta.protocol.layout.Parameter

/**
 * Tier 2 Compose enrichment (research D1/D2): composable names + source
 * location via ui-tooling-data's `asTree()`. Reached entirely by REFLECTION —
 * not compile-time tooling types — so the version-coupled surface can't break
 * compilation, and any runtime shape difference throws → the caller degrades
 * to Tier 1 (`compose-enrichment-failed`, all-or-none). Loaded only behind
 * ComposePresence's Tier-2 gate.
 *
 * The join key to the Tier-1 semantics tree is the layout node's semanticsId.
 */
class ToolingEnrichment {

    /** Builds the overlay keyed by semantics id, or throws (caller wraps in
     * EnrichmentOverlay.buildOrNull for all-or-none). */
    fun buildOverlay(host: View): Map<Long, EnrichmentEntry> {
        val tables = compositionDataSet(host) ?: error("no CompositionData on host")
        val asTree = Class.forName("androidx.compose.ui.tooling.data.SlotTreeKt")
            .methods.first { it.name == "asTree" }

        val out = HashMap<Long, EnrichmentEntry>()
        for (data in tables) {
            val group = asTree.invoke(null, data) ?: continue
            collect(group, Context(null, null, null, emptyList(), true), null, out)
        }
        if (out.isEmpty()) error("tooling produced no groups")
        return out
    }

    /** The nearest enclosing named composable while descending — a NodeGroup's
     * semantics id is labeled with this. */
    private class Context(
        val name: String?,
        val sourceFile: String?,
        val lineNumber: Int?,
        val parameters: List<Parameter>,
        val library: Boolean,
    )

    private fun collect(
        group: Any,
        inherited: Context,
        inheritedUser: Context?,
        out: MutableMap<Long, EnrichmentEntry>,
    ) {
        val rawName = group.call("getName") as? String
        val name = rawName?.let(::normalize)
        val location = group.call("getLocation")
        val sourceFile = location?.call("getSourceFile") as? String
        val line = (location?.call("getLineNumber") as? Int)?.takeIf { it > 0 }

        val meaningful = name != null && rawName !in SKIP_NAMES
        // Nearest meaningful composable (may be a library internal).
        val context = if (meaningful) {
            Context(name!!, sourceFile, line, parametersOf(group), isLibraryFile(sourceFile))
        } else {
            inherited
        }
        // Nearest USER composable (source file not a known framework file) —
        // this is what a developer wants to see labeling the node.
        val userContext = if (meaningful && !isLibraryFile(sourceFile)) context else inheritedUser

        val semanticsId = semanticsIdOf(group)
        if (semanticsId != null && !out.containsKey(semanticsId)) {
            val chosen = userContext ?: context
            if (chosen.name != null) {
                out[semanticsId] = EnrichmentEntry(
                    name = chosen.name,
                    sourceFile = chosen.sourceFile,
                    lineNumber = chosen.lineNumber,
                    parameters = chosen.parameters,
                    library = chosen.library,
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        (group.call("getChildren") as? Iterable<Any>)?.forEach { collect(it, context, userContext, out) }
    }

    /** A composable's source file is a framework file when it lives in a known
     * Compose/AndroidX file — user composables live elsewhere (the app). */
    private fun isLibraryFile(sourceFile: String?): Boolean {
        if (sourceFile == null) return true
        return sourceFile in LIBRARY_FILES
    }

    private fun semanticsIdOf(group: Any): Long? = try {
        // NodeGroup.node is the LayoutInfo; its getSemanticsId matches SemanticsNode.id.
        val node = group.javaClass.methods.firstOrNull { it.name == "getNode" && it.parameterCount == 0 }?.invoke(group)
        (node?.call("getSemanticsId") as? Int)?.toLong()
    } catch (_: Throwable) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parametersOf(group: Any): List<Parameter> = try {
        (group.call("getParameters") as? Iterable<Any>)?.mapNotNull { p ->
            val n = p.call("getName") as? String ?: return@mapNotNull null
            val v = p.call("getValue")
            Parameter(n, v?.toString() ?: "null", v?.let { it::class.simpleName } ?: "null")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun compositionDataSet(host: View): Iterable<Any>? {
        // Compose merges its resources into the app's package, so resolve the
        // tag id under the app package (not "androidx.compose.ui"). Fall back to
        // scanning both known packages for robustness across AGP namespacing.
        val res = host.resources
        val pkg = host.context.packageName
        val tagId = res.getIdentifier("inspection_slot_table_set", "id", pkg)
            .takeIf { it != 0 }
            ?: res.getIdentifier("inspection_slot_table_set", "id", "androidx.compose.ui")
                .takeIf { it != 0 }
            ?: return null
        return host.getTag(tagId) as? Iterable<Any>
    }

    private fun Any.call(method: String): Any? =
        javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }?.invoke(this)

    private fun normalize(name: String): String = when (name) {
        "BasicText" -> "Text"
        "BasicTextField" -> "TextField"
        else -> name
    }

    private companion object {
        // Node-emitting / layout primitives that must not become a node's label.
        val SKIP_NAMES = setOf(
            "ReusableComposeNode", "ComposeNode", "Layout", "LayoutNode",
            "SubcomposeLayout", "ReusableContentHost", "ReusableContent",
        )

        // Common Compose/Material/Foundation source files — composables from
        // these are framework internals, not the developer's own code.
        val LIBRARY_FILES = setOf(
            "Text.kt", "BasicText.kt", "BasicTextField.kt", "TextField.kt",
            "Surface.kt", "Box.kt", "Row.kt", "Column.kt", "Layout.kt",
            "Button.kt", "Card.kt", "Scaffold.kt", "AppBar.kt", "Icon.kt",
            "Image.kt", "Spacer.kt", "SelectionContainer.kt", "CoreText.kt",
            "AndroidView.kt", "ComposeView.android.kt", "Wrapper.android.kt",
            "SnackbarHost.kt", "ProvideTextStyle.kt", "TextStyle.kt",
        )
    }
}
