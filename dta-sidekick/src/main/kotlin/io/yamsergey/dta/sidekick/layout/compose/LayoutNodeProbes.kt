package io.yamsergey.dta.sidekick.layout.compose

import android.view.View
import androidx.compose.ui.layout.LayoutInfo

/**
 * Reflective reads on a LayoutInfo that are not stable public API across the
 * supported Compose range. One implementation, shared by the semantics walk
 * and the tooling layout records. Absence is normal and yields null.
 */
object LayoutNodeProbes {

    /** The id shared with SemanticsNode.id (the join key between tiers). */
    fun semanticsId(node: LayoutInfo): Long? = try {
        (io.yamsergey.dta.sidekick.util.NoArgMethods.call(node, "getSemanticsId") as? Int)?.toLong()
    } catch (_: Throwable) {
        null
    }

    /**
     * Drawing id of the classic View an AndroidView layout node hosts, as the
     * View walk keys it: the direct child of AndroidViewsHandler (the holder),
     * not the app's own View that getInteropView returns, which sits inside
     * the holder.
     */
    fun interopViewDrawingId(node: LayoutInfo): Long? = try {
        val interop = io.yamsergey.dta.sidekick.util.NoArgMethods.call(node, "getInteropView") as? View
        interop?.let { holderOf(it).drawingId() }
    } catch (_: Throwable) {
        null
    }

    private fun holderOf(view: View): View {
        var current = view
        while (true) {
            val parent = current.parent as? View ?: return view
            if (parent.javaClass.name == ANDROID_VIEWS_HANDLER) return current
            current = parent
        }
    }

    private fun View.drawingId(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 29) uniqueDrawingId else System.identityHashCode(this).toLong()

    private const val ANDROID_VIEWS_HANDLER = "androidx.compose.ui.platform.AndroidViewsHandler"
}
