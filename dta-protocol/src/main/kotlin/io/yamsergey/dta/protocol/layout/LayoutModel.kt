package io.yamsergey.dta.protocol.layout

/** Shared layout tree schema — one model for all capture sources (sidekick
 * views/compose + daemon fallback), spoken by both sides and future clients. */

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Enumerable degradation reason codes (research D8) so "zero silent
 * degradation" (SC-004) is testable against a fixed vocabulary. */
object DegradationCodes {
    const val COMPOSE_TOOLING_ABSENT = "compose-tooling-absent"
    const val COMPOSE_TOOLING_VERSION_SKEW = "compose-tooling-version-skew"
    const val COMPOSE_ENRICHMENT_FAILED = "compose-enrichment-failed"
    const val COMPOSE_FIRST_FRAME_PENDING = "compose-first-frame-pending"
    const val DEPTH_CAP_REACHED = "depth-cap-reached"
    const val WINDOW_LOST_MID_CAPTURE = "window-lost-mid-capture"
    const val LEGACY_ROOT_DISCOVERY = "legacy-root-discovery"
}

@Serializable
enum class CaptureSource {
    @SerialName("sidekick") SIDEKICK,
    @SerialName("uiautomator") UIAUTOMATOR,
}

@Serializable
enum class WindowType {
    @SerialName("application") APPLICATION,
    @SerialName("dialog") DIALOG,
    @SerialName("popup") POPUP,
    @SerialName("system") SYSTEM,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class Interaction {
    @SerialName("clickable") CLICKABLE,
    @SerialName("focusable") FOCUSABLE,
    @SerialName("scrollable") SCROLLABLE,
    @SerialName("long_clickable") LONG_CLICKABLE,
}

@Serializable
enum class Visibility {
    @SerialName("visible") VISIBLE,
    @SerialName("invisible") INVISIBLE,
}

@Serializable
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height
}

/** Four corners (x,y ×4) of a transformed node's rendered outline. */
@Serializable
data class Quad(
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val x3: Float, val y3: Float,
)

@Serializable
data class Parameter(val name: String, val value: String, val type: String)

@Serializable
data class Screen(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val activity: String? = null,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface Node {
    val id: Long?
    val bounds: Rect
    val text: String?
    val contentDescription: String?
    val interactions: List<Interaction>
    val enabled: Boolean
    val children: List<Node>
    val degraded: List<DegradationMarker>?
}

/** id source: View.uniqueDrawingId (API 29+); stable across unchanged screens. */
@Serializable
@SerialName("view")
data class ViewNode(
    override val id: Long? = null,
    override val bounds: Rect,
    val className: String,
    val resourceId: String? = null,
    override val text: String? = null,
    val hint: String? = null,
    override val contentDescription: String? = null,
    override val interactions: List<Interaction> = emptyList(),
    override val enabled: Boolean = true,
    val visibility: Visibility = Visibility.VISIBLE,
    val renderOutline: Quad? = null,
    override val children: List<Node> = emptyList(),
    override val degraded: List<DegradationMarker>? = null,
) : Node

/** id source: SemanticsNode id. name/sourceFile/lineNumber/parameters/library
 * are Tier-2 enrichment — all-or-none, null when enrichment absent. */
@Serializable
@SerialName("compose")
data class ComposeNode(
    override val id: Long? = null,
    override val bounds: Rect,
    val name: String? = null,
    val role: String? = null,
    val testTag: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val interactions: List<Interaction> = emptyList(),
    override val enabled: Boolean = true,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val library: Boolean? = null,
    val parameters: List<Parameter>? = null,
    override val children: List<Node> = emptyList(),
    override val degraded: List<DegradationMarker>? = null,
) : Node

/** Fallback nodes carry no id guarantee (uiautomator has no stable identity). */
@Serializable
@SerialName("fallback")
data class FallbackNode(
    override val id: Long? = null,
    override val bounds: Rect,
    val className: String,
    val resourceId: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val interactions: List<Interaction> = emptyList(),
    override val enabled: Boolean = true,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    override val children: List<Node> = emptyList(),
    override val degraded: List<DegradationMarker>? = null,
) : Node

@Serializable
data class Window(
    val title: String? = null,
    val type: WindowType,
    val zOrder: Int,
    val root: Node,
)

@Serializable
data class AncestorRef(
    val kind: String,
    val label: String,
    val id: Long? = null,
    val bounds: Rect,
)

@Serializable
data class Match(
    val window: Int,
    val node: Node,
    val ancestors: List<AncestorRef> = emptyList(),
)

@Serializable
data class LayoutCapture(
    val screen: Screen,
    val source: CaptureSource,
    val windows: List<Window>,
    val fallbackReason: String? = null,
    val foregroundMismatch: String? = null,
    val degraded: List<DegradationMarker>? = null,
    val filtersApplied: Boolean = false,
    val matches: List<Match>? = null,
)
