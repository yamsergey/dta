package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutTreeParams
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/**
 * Host-side orchestration (contract §4): choose the sidekick path or the
 * labeled uiautomator fallback, and NEVER let a substitution be silent —
 * every fallback carries its source and, when it replaced something better,
 * the reason. The daemon adds labels/fallback/reasons only; it never mutates
 * a sidekick tree.
 *
 * Collaborators injected as functions so the whole ladder is deviceless.
 */
class LayoutService(
    private val foregroundPackage: (serial: String) -> String?,
    private val sidekickCapture: (suspend (LayoutTreeParams) -> LayoutCapture)?,
    private val uiautomatorCapture: (serial: String) -> Node,
    private val screenOf: (serial: String) -> Screen,
) {
    suspend fun capture(serial: String, pkg: String?, params: LayoutTreeParams = LayoutTreeParams()): LayoutCapture {
        val foreground = foregroundPackage(serial)

        // 4: no package / no sidekick → plain fallback, nothing better expected.
        if (pkg == null || sidekickCapture == null) {
            return fallback(serial, params, fallbackReason = null, foregroundMismatch = null)
        }

        // 3: requested app not foreground → fallback of the actual screen + note.
        if (foreground != null && foreground != pkg) {
            return fallback(
                serial, params,
                fallbackReason = null,
                foregroundMismatch = "requested $pkg but $foreground is foreground",
            )
        }

        // 1/2: try sidekick; on failure → labeled fallback carrying the reason.
        return try {
            sidekickCapture.invoke(params)
        } catch (e: Exception) {
            fallback(
                serial, params,
                fallbackReason = e.message ?: "sidekick capture failed (${e.javaClass.simpleName})",
                foregroundMismatch = null,
            )
        }
    }

    private fun fallback(
        serial: String,
        params: LayoutTreeParams,
        fallbackReason: String?,
        foregroundMismatch: String?,
    ): LayoutCapture {
        val root = uiautomatorCapture(serial)
        val degraded = buildList {
            if (params.filter != null) {
                add(DegradationMarker("layout", "filter-unavailable-on-fallback: filters cannot be evaluated on a uiautomator dump"))
            }
        }.ifEmpty { null }
        return LayoutCapture(
            screen = screenOf(serial),
            source = CaptureSource.UIAUTOMATOR,
            windows = listOf(Window(title = null, type = WindowType.APPLICATION, zOrder = 0, root = root)),
            fallbackReason = fallbackReason,
            foregroundMismatch = foregroundMismatch,
            filtersApplied = false,
            degraded = degraded,
        )
    }
}
