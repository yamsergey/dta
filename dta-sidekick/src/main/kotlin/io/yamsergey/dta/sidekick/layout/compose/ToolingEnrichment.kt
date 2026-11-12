package io.yamsergey.dta.sidekick.layout.compose

import android.view.View
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.layout.LayoutInfo
import io.yamsergey.dta.protocol.layout.Parameter
import java.lang.reflect.Method

/**
 * Tier 2 Compose enrichment (research D1/D2, feature 003 R1): one traversal
 * per CompositionData via ui-tooling-data's `mapTree`, whose factory builds the
 * pure [ToolingGroup] intermediate. The tooling surface is reached entirely by
 * REFLECTION — not compile-time tooling types — so the version-coupled surface
 * can't break compilation, and any runtime shape difference throws → the
 * caller degrades to Tier 1 (`compose-enrichment-failed`, all-or-none). Loaded
 * only behind ComposePresence's Tier-2 gate.
 *
 * The join key to the Tier-1 semantics tree is the layout node's semanticsId.
 */
class ToolingEnrichment(private val classifier: CallClassifier = CallClassifier(null, null)) {

    /** The tooling forest of one host plus the layout nodes its groups own,
     * keyed by semanticsId (read later for container geometry). */
    class Read(val groups: List<ToolingGroup>, val layoutNodes: Map<Long, LayoutInfo>)

    /** Reads the host's tooling forest, or throws (the caller discards all
     * tooling data for the host: all-or-none). */
    fun read(host: View): Read {
        val tables = compositionDataSet(host) ?: error("no CompositionData on host")
        val api = Api.get()
        val cache = api.newCache()
        val layoutNodes = HashMap<Long, LayoutInfo>()
        val factory: (Any?, Any?, List<Any?>) -> Any? = { group, context, children ->
            buildGroup(api, group as CompositionGroup, context!!, children, layoutNodes)
        }
        val groups = tables.mapNotNull { data -> api.mapTree.invoke(null, data, factory, cache) as ToolingGroup? }
        return Read(groups, layoutNodes)
    }

    /** mapTree factory body. Never returns null (null drops the subtree). */
    private fun buildGroup(
        api: Api,
        group: CompositionGroup,
        context: Any,
        children: List<Any?>,
        layoutNodes: MutableMap<Long, LayoutInfo>,
    ): ToolingGroup {
        val name = api.name.invoke(context) as String?
        val location = api.location.invoke(context)
        val sourceFile = location?.let { api.sourceFile.invoke(it) } as String?
        // Documented as 0-based, but measured 1-based on device (Compose 1.11,
        // research T015 note): used as-is; 0 means unknown.
        val line = (location?.let { api.lineNumber.invoke(it) } as Int?)?.takeIf { it > 0 }

        val packageHash = (location?.let { api.packageHash.invoke(it) } as Int?) ?: -1

        // Feature 054: each meaningful call classified once (and counted when unknown).
        val origin = if (ComposableClassifier.isMeaningful(name)) classifier.of(sourceFile, packageHash) else null
        val userFrame = origin != null && !origin.library
        val parameters = if (userFrame) parametersOf(api, context) else emptyList()

        val layoutId = (group.node as? LayoutInfo)?.let { node ->
            LayoutNodeProbes.semanticsId(node)?.also { layoutNodes.putIfAbsent(it, node) }
        }
        return ToolingGroup(
            name = name,
            sourceFile = sourceFile,
            line = line,
            parameters = parameters,
            layoutId = layoutId,
            children = children.map { it as ToolingGroup },
            packageHash = packageHash,
            origin = origin,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parametersOf(api: Api, context: Any): List<Parameter> = try {
        (api.parameters.invoke(context) as? Iterable<Any>)?.mapNotNull { p ->
            val n = p.call("getName") as? String ?: return@mapNotNull null
            // Feature 018: readable values; null left out; defaults marked; packed inline classes decoded.
            ParameterValues.render(n, p.call("getValue"), p.call("getInlineClass") as? String, p.call("getFromDefault") == true)
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

    private fun Any.call(method: String): Any? = io.yamsergey.dta.sidekick.util.NoArgMethods.call(this, method)

    /** Reflective handles on ui-tooling-data, resolved once. Methods are taken
     * from the public interface/data classes: the SourceContext implementation
     * is a private class whose own Method objects are not invokable. */
    private class Api(
        val mapTree: Method,
        private val cacheClass: Class<*>,
        val name: Method,
        val location: Method,
        val parameters: Method,
        val sourceFile: Method,
        val lineNumber: Method,
        val packageHash: Method,
    ) {
        fun newCache(): Any = cacheClass.getDeclaredConstructor().newInstance()

        companion object {
            private const val PKG = "androidx.compose.ui.tooling.data"

            @Volatile private var instance: Api? = null

            fun get(): Api = instance ?: create().also { instance = it }

            private fun create(): Api {
                val slotTree = Class.forName("$PKG.SlotTreeKt")
                val cache = Class.forName("$PKG.ContextCache")
                val context = Class.forName("$PKG.SourceContext")
                val location = Class.forName("$PKG.SourceLocation")
                return Api(
                    mapTree = slotTree.getMethod(
                        "mapTree",
                        Class.forName("androidx.compose.runtime.tooling.CompositionData"),
                        Function3::class.java,
                        cache,
                    ),
                    cacheClass = cache,
                    name = context.getMethod("getName"),
                    location = context.getMethod("getLocation"),
                    parameters = context.getMethod("getParameters"),
                    sourceFile = location.getMethod("getSourceFile"),
                    lineNumber = location.getMethod("getLineNumber"),
                    packageHash = location.getMethod("getPackageHash"),
                )
            }
        }
    }
}
