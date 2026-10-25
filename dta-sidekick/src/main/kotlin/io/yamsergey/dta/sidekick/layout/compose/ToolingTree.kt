package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.Parameter
import io.yamsergey.dta.protocol.layout.Rect

/**
 * One composition group as produced by the mapTree factory (research R1).
 * Pure: no tooling or Android types, so everything built on it is tested
 * deviceless. [name] is the raw composable name; [sourceFile]/[line] are the
 * call site (1-based line); [parameters] are extracted for user frames only;
 * [layoutId] is the semanticsId of the layout node a node-emitting group owns.
 */
data class ToolingGroup(
    val name: String?,
    val sourceFile: String?,
    val line: Int?,
    val parameters: List<Parameter>,
    val layoutId: Long?,
    val children: List<ToolingGroup>,
)

/** A layout node discovered through tooling (feature 003 `layout` level):
 * its actual layout parent comes from LayoutInfo.parentInfo, not from slot
 * table nesting (research R2). */
data class LayoutRecord(
    val id: Long,
    val parentId: Long?,
    val bounds: Rect,
    val placed: Boolean,
    val interopViewId: Long? = null,
)

/** Pure assembly over the [ToolingGroup] forest. */
object ToolingTree {

    private class Frame(
        val name: String,
        val sourceFile: String?,
        val line: Int?,
        val parameters: List<Parameter>,
        val library: Boolean,
    )

    /**
     * Labels every layout id with the nearest enclosing USER composable, or
     * the nearest meaningful library composable when no user one encloses it.
     * The first occurrence of an id wins.
     */
    fun label(roots: List<ToolingGroup>): Map<Long, EnrichmentEntry> {
        val out = HashMap<Long, EnrichmentEntry>()
        fun walk(group: ToolingGroup, nearest: Frame?, nearestUser: Frame?) {
            val frame = if (ComposableClassifier.isMeaningful(group.name)) {
                Frame(
                    ComposableClassifier.normalize(group.name!!),
                    group.sourceFile,
                    group.line,
                    group.parameters,
                    ComposableClassifier.isLibrary(group.sourceFile),
                )
            } else {
                null
            }
            val context = frame ?: nearest
            val userContext = if (frame != null && !frame.library) frame else nearestUser

            val id = group.layoutId
            if (id != null && id !in out) {
                (userContext ?: context)?.let {
                    out[id] = EnrichmentEntry(it.name, it.sourceFile, it.line, it.parameters, it.library)
                }
            }
            group.children.forEach { walk(it, context, userContext) }
        }
        roots.forEach { walk(it, null, null) }
        return out
    }

    /** True when any group carries a source location; false means the app
     * was built without source information (names may still be present). */
    fun hasSourceInfo(roots: List<ToolingGroup>): Boolean {
        fun any(g: ToolingGroup): Boolean = g.sourceFile != null || g.children.any(::any)
        return roots.any(::any)
    }
}
