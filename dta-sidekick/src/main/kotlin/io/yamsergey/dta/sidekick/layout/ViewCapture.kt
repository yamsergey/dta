package io.yamsergey.dta.sidekick.layout

import android.graphics.Matrix
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import io.yamsergey.dta.protocol.layout.Interaction
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Quad
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.layout.Visibility
import io.yamsergey.dta.protocol.layout.WindowType
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/** One captured window: its metadata + the raw view-walk intermediate. */
class CapturedWindow(
    val title: String?,
    val type: WindowType,
    val zOrder: Int,
    val walk: ViewWalkResult,
    /** AndroidComposeView roots keyed by their host drawing id, for Tier 1/2. */
    val composeHosts: Map<Long, View>,
)

/**
 * Classic View capture, ported from the AS View agent (research D3). Runs on
 * the main thread (caller guarantees). Produces wire ViewNodes plus the
 * out-of-band compose-host ids and embedded-view roots the TreeMerger needs.
 */
class ViewCapture(private val maxDepth: Int = 100) {

    private val composeViewClass = "androidx.compose.ui.platform.AndroidComposeView"
    private val androidViewsHandlerClass = "androidx.compose.ui.platform.AndroidViewsHandler"

    fun captureWindows(): List<CapturedWindow> {
        val roots = discoverRoots()
        return roots.mapIndexed { index, view ->
            val hosts = mutableMapOf<Long, View>()
            val embedded = mutableMapOf<Long, Node>()
            val hostIds = mutableSetOf<Long>()
            val root = walk(view, 0, hosts, embedded, hostIds)
            CapturedWindow(
                title = windowTitle(view),
                type = windowType(view),
                zOrder = index,
                walk = ViewWalkResult(root, embedded, hostIds),
                composeHosts = hosts,
            )
        }
    }

    private fun discoverRoots(): List<View> {
        val selection = RootProviderSelector.select(Build.VERSION.SDK_INT)
        val views = when (selection.kind) {
            RootProviderKind.WINDOW_INSPECTOR -> WindowInspector.getGlobalWindowViews()
            RootProviderKind.REFLECTION -> reflectRoots()
        }
        return views.filter { it.visibility == View.VISIBLE && it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            .sortedBy { it.z }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reflectRoots(): List<View> = try {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmg.getMethod("getInstance").invoke(null)
        val field = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        (field.get(instance) as? List<View>)?.toList() ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun walk(
        view: View,
        depth: Int,
        hosts: MutableMap<Long, View>,
        embedded: MutableMap<Long, Node>,
        hostIds: MutableSet<Long>,
    ): Node {
        val id = view.uniqueDrawingIdCompat()
        val className = view::class.java.name

        if (className == composeViewClass) {
            hosts[id] = view
            hostIds += id
            // The compose subtree is captured via semantics, but classic Views
            // embedded in it (AndroidView) live under AndroidViewsHandler in the
            // ComposeView's View descendants — scan for them so the merger can
            // reparent them (they'd otherwise be lost at this boundary).
            if (view is ViewGroup) scanEmbedded(view, hosts, embedded, hostIds)
            return viewNode(view, id, className, emptyList(), null)
        }

        if (depth >= maxDepth) {
            return viewNode(
                view, id, className, emptyList(),
                degraded = listOf(DegradationMarker("layout", io.yamsergey.dta.protocol.layout.DegradationCodes.DEPTH_CAP_REACHED)),
            )
        }

        // AndroidViewsHandler hosts classic Views embedded in Compose — record
        // each so the merger can reparent it under its Compose parent by id.
        if (className == androidViewsHandlerClass && view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                embedded[child.uniqueDrawingIdCompat()] = walk(child, depth + 1, hosts, embedded, hostIds)
            }
            return viewNode(view, id, className, emptyList(), null)
        }

        val children = if (view is ViewGroup) {
            (0 until view.childCount)
                .map { view.getChildAt(it) }
                .filter { it.visibility != View.GONE }
                .map { walk(it, depth + 1, hosts, embedded, hostIds) }
        } else {
            emptyList()
        }
        return viewNode(view, id, className, children, null)
    }

    /** Recursively scans a ComposeView's View descendants for
     * AndroidViewsHandler and records each embedded classic View subtree. */
    private fun scanEmbedded(
        group: ViewGroup,
        hosts: MutableMap<Long, View>,
        embedded: MutableMap<Long, Node>,
        hostIds: MutableSet<Long>,
    ) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child::class.java.name == androidViewsHandlerClass && child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    val embeddedView = child.getChildAt(j)
                    if (embeddedView.visibility != View.GONE) {
                        embedded[embeddedView.uniqueDrawingIdCompat()] =
                            walk(embeddedView, 0, hosts, embedded, hostIds)
                    }
                }
            } else if (child is ViewGroup) {
                scanEmbedded(child, hosts, embedded, hostIds)
            }
        }
    }

    private fun viewNode(
        view: View,
        id: Long,
        className: String,
        children: List<Node>,
        degraded: List<DegradationMarker>?,
    ): ViewNode {
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val bounds = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        val interactions = buildList {
            if (view.isClickable) add(Interaction.CLICKABLE)
            if (view.isLongClickable) add(Interaction.LONG_CLICKABLE)
            if (view.isFocusable) add(Interaction.FOCUSABLE)
            if (view.isScrollContainer) add(Interaction.SCROLLABLE)
        }
        return ViewNode(
            id = id,
            bounds = bounds,
            className = className,
            resourceId = resourceId(view),
            text = (view as? android.widget.TextView)?.text?.toString()?.ifEmpty { null },
            hint = (view as? android.widget.TextView)?.hint?.toString()?.ifEmpty { null },
            contentDescription = view.contentDescription?.toString()?.ifEmpty { null },
            interactions = interactions,
            enabled = view.isEnabled,
            visibility = if (view.visibility == View.VISIBLE) Visibility.VISIBLE else Visibility.INVISIBLE,
            renderOutline = renderOutline(view, loc),
            children = children,
            degraded = degraded,
        )
    }

    private fun renderOutline(view: View, loc: IntArray): Quad? {
        val m = Matrix()
        view.transformMatrixToGlobal(m)
        // transformMatrixToGlobal always carries the screen translation, so
        // isIdentity is never true. Only a genuine rotation/scale/skew (the
        // non-translation components) warrants a distinct rendered outline.
        val v = FloatArray(9).also { m.getValues(it) }
        val rotatedOrScaled = kotlin.math.abs(v[Matrix.MSCALE_X] - 1f) > 1e-3f ||
            kotlin.math.abs(v[Matrix.MSCALE_Y] - 1f) > 1e-3f ||
            kotlin.math.abs(v[Matrix.MSKEW_X]) > 1e-3f ||
            kotlin.math.abs(v[Matrix.MSKEW_Y]) > 1e-3f
        if (!rotatedOrScaled) return null
        val pts = floatArrayOf(0f, 0f, view.width.toFloat(), 0f, view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat())
        m.mapPoints(pts)
        return Quad(pts[0], pts[1], pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
    }

    private fun resourceId(view: View): String? {
        val rid = view.id
        if (rid == View.NO_ID) return null
        return try {
            val res = view.resources
            "${res.getResourcePackageName(rid)}:${res.getResourceTypeName(rid)}/${res.getResourceEntryName(rid)}"
        } catch (_: Throwable) {
            null
        }
    }

    private fun View.uniqueDrawingIdCompat(): Long =
        if (Build.VERSION.SDK_INT >= 29) uniqueDrawingId else System.identityHashCode(this).toLong()

    private fun windowTitle(view: View): String? =
        (view.context as? android.app.Activity)?.javaClass?.simpleName
            ?: view.rootView?.javaClass?.simpleName

    private fun windowType(view: View): WindowType {
        val ctx = view.context
        return when {
            ctx is android.app.Activity -> WindowType.APPLICATION
            view.javaClass.name.contains("PopupDecor") -> WindowType.POPUP
            else -> WindowType.APPLICATION
        }
    }
}
