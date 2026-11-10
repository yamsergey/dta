package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.Frame as WireFrame
import io.yamsergey.dta.protocol.layout.Parameter
import io.yamsergey.dta.protocol.layout.Rect

/**
 * One composition group as produced by the mapTree factory (research R1).
 * Pure: no tooling or Android types, so everything built on it is tested
 * deviceless. [name] is the raw composable name; [sourceFile]/[line] are the
 * call site (1-based line) and [packageHash] the call site's package hash
 * (-1 unknown); [parameters] are extracted for user frames only; [layoutId] is
 * the semanticsId of the layout node a node-emitting group owns.
 */
data class ToolingGroup(
    val name: String?,
    val sourceFile: String?,
    val line: Int?,
    val parameters: List<Parameter>,
    val layoutId: Long?,
    val children: List<ToolingGroup>,
    val packageHash: Int = -1,
    /** Feature 054: the call's origin as classified at capture; null → the fixed list decides. */
    val origin: CallOrigin? = null,
) {
    /** [origin], or the pre-054 verdict for groups built without one. */
    val resolvedOrigin: CallOrigin
        get() = origin ?: CallOrigin(if (ComposableClassifier.isLibrary(sourceFile, packageHash)) CallOrigin.Kind.LIBRARY else CallOrigin.Kind.APP)
}

/** A layout node discovered through tooling (feature 003 `layout` level):
 * its actual layout parent comes from LayoutInfo.parentInfo, not from slot
 * table nesting (research R2). */
data class LayoutRecord(
    val id: Long,
    val parentId: Long?,
    val bounds: Rect,
    val placed: Boolean,
    val interopViewId: Long? = null,
    /** false when clipping to the window leaves nothing of the node. */
    val onScreen: Boolean = true,
)

/** Pure assembly over the [ToolingGroup] forest. */
object ToolingTree {

    private class Frame(
        val name: String,
        val sourceFile: String?,
        val line: Int?,
        val parameters: List<Parameter>,
        val origin: CallOrigin,
        /** One per call (feature 018): every layout node the call owns shares it. */
        val callKey: Int,
    )

    /**
     * Labels every layout id with the nearest enclosing APP composable, else
     * the nearest one whose package is unknown (feature 054, marked), else the
     * nearest meaningful library composable. The first occurrence of an id wins.
     */
    fun label(roots: List<ToolingGroup>): Map<Long, EnrichmentEntry> {
        val out = HashMap<Long, EnrichmentEntry>()
        var calls = 0
        fun walk(group: ToolingGroup, nearest: Frame?, nearestApp: Frame?, nearestUnknown: Frame?) {
            val frame = if (ComposableClassifier.isMeaningful(group.name)) {
                Frame(
                    ComposableClassifier.normalize(group.name!!),
                    group.sourceFile,
                    group.line,
                    group.parameters,
                    group.resolvedOrigin,
                    ++calls,
                )
            } else {
                null
            }
            val context = frame ?: nearest
            val appContext = if (frame?.origin?.kind == CallOrigin.Kind.APP) frame else nearestApp
            val unknownContext = if (frame?.origin?.kind == CallOrigin.Kind.UNKNOWN) frame else nearestUnknown

            val id = group.layoutId
            if (id != null && id !in out) {
                (appContext ?: unknownContext ?: context)?.let {
                    out[id] = EnrichmentEntry(
                        it.name, it.sourceFile, it.line, it.parameters, it.origin.library, it.callKey,
                        packageName = it.origin.packageName, unknown = it.origin.unknown,
                    )
                }
            }
            group.children.forEach { walk(it, context, appContext, unknownContext) }
        }
        roots.forEach { walk(it, null, null, null) }
        return out
    }

    /** True when any group carries a source location; false means the app
     * was built without source information (names may still be present). */
    fun hasSourceInfo(roots: List<ToolingGroup>): Boolean {
        fun any(g: ToolingGroup): Boolean = g.sourceFile != null || g.children.any(::any)
        return roots.any(::any)
    }

    /** Call chains keyed by layout id; [unresolvedPrefix] is true when some
     * subcomposition's host could not be found (its chains lack the prefix). */
    data class Chains(val chains: Map<Long, List<WireFrame>>, val unresolvedPrefix: Boolean)

    /**
     * Root → node chain of composables for every layout id, spanning
     * subcompositions: each composition (one root per CompositionData) is
     * prefixed by the chain of the layout node hosting it, found through the
     * layout parent links in [records] (one linking mechanism with
     * containers). User frames only unless [frameworkFrames].
     */
    fun chains(roots: List<ToolingGroup>, records: List<LayoutRecord>, frameworkFrames: Boolean): Chains {
        val local = HashMap<Long, List<WireFrame>>()
        val owner = HashMap<Long, Int>()
        roots.forEachIndexed { index, root ->
            fun walk(group: ToolingGroup, stack: List<WireFrame>) {
                var next = stack
                if (ComposableClassifier.isMeaningful(group.name)) {
                    val origin = group.resolvedOrigin
                    if (!origin.library || frameworkFrames) {
                        next = stack + WireFrame(
                            ComposableClassifier.normalize(group.name!!), group.sourceFile, group.line,
                            library = origin.library.takeIf { it },
                            packageName = origin.packageName,
                            unknownOrigin = origin.unknown.takeIf { it },
                        )
                    }
                }
                group.layoutId?.let { id ->
                    if (id !in local) {
                        local[id] = next
                        owner[id] = index
                    }
                }
                group.children.forEach { walk(it, next) }
            }
            walk(root, emptyList())
        }

        val parentOf = records.associate { it.id to it.parentId }
        var unresolved = false

        // The layout id hosting composition [index]: the nearest owned
        // ancestor of its first top-level layout node outside the composition.
        // Null = root composition (chain reaches the host root) or unknown.
        val hostOf = HashMap<Int, Long?>()
        fun host(index: Int): Long? = hostOf.getOrPut(index) {
            val top = local.keys.firstOrNull { owner[it] == index && owner[parentOf[it]] != index } ?: return@getOrPut null
            var p = parentOf[top]
            val seen = HashSet<Long>()
            while (p != null && seen.add(p)) {
                if (p !in parentOf) {
                    unresolved = true
                    return@getOrPut null
                }
                if (owner[p] != null && owner[p] != index) return@getOrPut p
                p = parentOf[p]
            }
            null
        }

        val full = HashMap<Long, List<WireFrame>>()
        val resolving = HashSet<Long>()
        fun chain(id: Long): List<WireFrame> = full[id] ?: run {
            val own = local.getValue(id)
            val hostId = owner[id]?.let(::host)
            val prefix = if (hostId != null && resolving.add(id)) chain(hostId).also { resolving.remove(id) } else emptyList()
            (prefix + own).also { full[id] = it }
        }
        local.keys.forEach { chain(it) }
        return Chains(full, unresolved)
    }
}
