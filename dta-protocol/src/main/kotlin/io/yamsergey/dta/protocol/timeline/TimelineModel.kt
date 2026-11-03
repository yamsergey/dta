package io.yamsergey.dta.protocol.timeline

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.ui.ElementRef
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.protocol.ui.UiCosts
import io.yamsergey.dta.protocol.ui.UiEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Feature 016: the timeline. One envelope for every source (research R1): 015's [UiEvent] is
 * one payload; sources and kinds are open strings so an app's own source needs no protocol
 * change. Facet changes and causes travel inside the event that made them.
 */
object Sources {
    const val INPUT = "input"
    const val UI = "ui"
    const val SCREEN = "screen"
    const val NETWORK = "network"
    const val NAV = "nav"
    const val APP = "app"

    /** Feature 027: what DTA itself changed in the app (data writes). */
    const val DTA = "dta"

    /** Gaps, process ends, facet snapshots: the timeline's own events. */
    const val TIMELINE = "timeline"

    val BUILT_IN = listOf(INPUT, UI, SCREEN, NETWORK, NAV, APP, DTA)
}

object Kinds {
    const val CLICK = "input/click"
    const val BACK = "input/back"
    const val NETWORK_REQUEST = "network/request"
    const val NETWORK_RESPONSE = "network/response"
    const val NETWORK_FAILED = "network/failed"
    const val NAV_NAVIGATE = "nav/navigate"
    const val NAV_BACK = "nav/back"
    const val FACET_CHANGED = "facet/changed"
    const val FACET_SNAPSHOT = "facet/snapshot"
    /** The app declared that one of its events caused another, after both (`app.attrs["to"]` is the effect). */
    const val DECLARED_CAUSE = "cause/declared"
    const val GAP = "timeline/gap"
    const val PROCESS_ENDED = "timeline/process-ended"

    /** `ui/<015 kind>`, e.g. `ui/window-appeared`, `ui/toast`. */
    fun ui(kind: String) = "ui/$kind"

    /** `app/<name>`, e.g. `app/cart.removed`. */
    fun app(name: String) = "app/$name"
}

object Facets {
    const val UI_WINDOWS = "ui.windows"
    const val SCREEN_FINGERPRINT = "screen.fingerprint"
    const val NAV_DESTINATION = "nav.destination"
    const val NAV_ARGS = "nav.args"
    const val NAV_KEY = "nav.key"

    /** Facets left out of a state graph unless chosen (contract `state.graph`). */
    val NOT_DEFAULT_IN_GRAPH = setOf(NAV_ARGS, UI_WINDOWS)

    fun app(name: String) = "app.$name"

    /** The source a facet name belongs to: its first segment. */
    fun sourceOf(facet: String): String = facet.substringBefore('.')

    /**
     * The facet values at the start of [events] (features 016/017): a value's first change says
     * what it was before; a facet that never changes in [events] has its [current] value.
     */
    fun initialValues(events: List<TimelineEvent>, current: List<FacetValue>): List<FacetValue> {
        val firstChange = LinkedHashMap<String, String?>()
        for (e in events) for (c in e.facets) if (c.name !in firstChange) firstChange[c.name] = c.previous
        val byName = current.associateBy { it.name }.toMutableMap()
        for ((name, before) in firstChange) {
            byName[name] = (byName[name] ?: FacetValue(name, null, sourceOf(name), 0, 0)).copy(value = before)
        }
        return byName.values.toList()
    }
}

object CauseRules {
    const val ISSUED_DURING_CLICK = "issued-during:click"
    const val ISSUED_DURING_BACK = "issued-during:back"
    const val SAME_EXCHANGE = "same-exchange"
    const val SAME_WINDOW = "same-window"
    const val APP_DECLARED = "app-declared"
    const val APP_SCOPE = "app-scope"
    const val AFTER_RESPONSE = "after-response"
    const val WHILE_IN_FLIGHT = "while-in-flight"
    const val TEXT_MATCH = "text-match"

    fun issuedDuring(kind: String) = "issued-during:${kind.substringAfter('/')}"
}

object Bounds {
    const val TITLE = 120
    const val FACET_VALUE = 120
    const val NAME = 64
    const val ATTRS = 8
}

@Serializable
enum class Basis {
    @SerialName("exact") EXACT,
    @SerialName("declared") DECLARED,
    @SerialName("inferred") INFERRED,
}

@Serializable
data class CauseLink(
    val from: String,
    val basis: Basis,
    val rule: String,
    val detail: String? = null,
    val declaredBy: String? = null,
    /** The far end is not in the timeline (evicted, never reported, another process). */
    val fromUnknown: Boolean = false,
)

@Serializable
data class FacetChange(
    val name: String,
    val value: String?,
    val previous: String? = null,
    val cut: Boolean = false,
)

@Serializable
enum class InputAction {
    @SerialName("click") CLICK,
    @SerialName("tap") TAP,
    @SerialName("back") BACK,
}

@Serializable
data class InputPayload(
    val action: InputAction,
    val element: ElementRef? = null,
    val text: String? = null,
    val windowId: String? = null,
    /** `view`, `compose`, `dispatcher`, `activity`. */
    val via: String,
)

@Serializable
enum class NetworkPhase {
    @SerialName("request") REQUEST,
    @SerialName("response") RESPONSE,
    @SerialName("failed") FAILED,
}

@Serializable
data class NetworkPayload(
    val entryId: String,
    val phase: NetworkPhase,
    val method: String? = null,
    val host: String? = null,
    /** Without query values. */
    val path: String? = null,
    val status: Int? = null,
    val durationMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class NavPayload(
    /** `navigation` or `navigation3`. */
    val library: String,
    /** `navigate`, `back`, `replace`. */
    val action: String,
    val destination: String,
    val args: Map<String, String> = emptyMap(),
    val depth: Int? = null,
)

@Serializable
data class AppPayload(
    val name: String,
    val attrs: Map<String, String> = emptyMap(),
    val localId: Long = 0,
)

@Serializable
data class TimelineEvent(
    /** `<epoch>:<seq>`; host-sampled events `<session>:h<seq>` (as in 015). */
    val id: String,
    val seq: Long,
    val monoMs: Long,
    val wallMs: Long,
    val source: String,
    val kind: String,
    val title: String = "",
    val ui: UiEvent? = null,
    val input: InputPayload? = null,
    val network: NetworkPayload? = null,
    val nav: NavPayload? = null,
    val app: AppPayload? = null,
    val facets: List<FacetChange> = emptyList(),
    val causes: List<CauseLink> = emptyList(),
    /** A gap: events of [gapSource] (all when null) that were not recorded. */
    val gapCount: Int? = null,
    val gapFromMs: Long? = null,
    val gapToMs: Long? = null,
    val gapSource: String? = null,
    val completeness: List<DegradationMarker> = emptyList(),
)

@Serializable
data class SourceStatus(
    val name: String,
    val state: SourceState,
    val reason: String? = null,
    val detail: String? = null,
)

@Serializable
data class TimelineBoundsInfo(
    val sidekickRing: Int? = null,
    val hostBound: Int? = null,
)

@Serializable
data class TimelinePage(
    val events: List<TimelineEvent>,
    val next: String,
    val more: Boolean = false,
    val sources: List<SourceStatus> = emptyList(),
    val bounds: TimelineBoundsInfo = TimelineBoundsInfo(),
)

/** The sidekick's answer to `timeline_read` (replaces 015's `UiJournalRead`). */
@Serializable
data class TimelineRead(
    val epoch: Long,
    val events: List<TimelineEvent>,
    val dropped: Int = 0,
    val lastSeq: Long = 0,
    val sources: List<SourceStatus> = emptyList(),
    val costs: TimelineCosts? = null,
    val ring: Int? = null,
    /** The current value of every facet the sidekick owns (research R10): seeds the host's table. */
    val facets: List<FacetValue> = emptyList(),
)

@Serializable
data class TimelineCosts(
    val summaries: UiCosts? = null,
    val scopes: Int = 0,
    val scopeMedianUs: Long = 0,
    val scopeMaxUs: Long = 0,
    val staleScopesDropped: Int = 0,
    val bridgeCalls: Long = 0,
)

@Serializable
data class Stale(val sinceMonoMs: Long, val reason: String)

@Serializable
data class FacetValue(
    val name: String,
    val value: String?,
    val source: String,
    val changedMonoMs: Long,
    val changedWallMs: Long,
    val changedBy: String? = null,
    val stale: Stale? = null,
    val cut: Boolean = false,
)

@Serializable
data class Disagreement(val facets: List<String>, val detail: String)

@Serializable
data class CurrentState(
    val facets: List<FacetValue>,
    val sources: List<SourceStatus>,
    val disagreements: List<Disagreement> = emptyList(),
)

@Serializable
enum class ChainDirection {
    @SerialName("causes") CAUSES,
    @SerialName("effects") EFFECTS,
}

@Serializable
data class ChainLink(val from: String, val to: String, val link: CauseLink)

@Serializable
enum class CutReason {
    @SerialName("evicted") EVICTED,
    @SerialName("unknown-event") UNKNOWN_EVENT,
    @SerialName("depth") DEPTH,
    @SerialName("process-boundary") PROCESS_BOUNDARY,
}

@Serializable
data class Cut(val at: String, val reason: CutReason, val detail: String? = null)

@Serializable
data class CauseChain(
    val root: TimelineEvent,
    val direction: ChainDirection,
    val nodes: List<TimelineEvent>,
    val links: List<ChainLink>,
    /** Ids of nodes with no exact or declared cause. */
    val noKnownCause: List<String> = emptyList(),
    /** Inferred candidates per node, given even when inferred links were not asked for. */
    val inferredCandidates: Map<String, Int> = emptyMap(),
    val cuts: List<Cut> = emptyList(),
    val inferredIncluded: Boolean = false,
)

@Serializable
data class StateRef(val id: String, val values: Map<String, String?>, val label: String)

@Serializable
data class StateNode(val state: StateRef, val entries: Int, val heldMs: Long, val current: Boolean = false)

@Serializable
data class Transition(
    val from: String,
    val to: String,
    val count: Int,
    /** Latest first, at most 5. */
    val triggers: List<String>,
    val causes: List<CauseLink> = emptyList(),
    val noKnownCause: Boolean = false,
    val selfLoop: Boolean = false,
)

@Serializable
data class GraphWindow(val fromMonoMs: Long, val toMonoMs: Long, val partial: Boolean = false)

@Serializable
data class StateGraph(
    val facets: List<String>,
    val unavailable: List<String> = emptyList(),
    val nodes: List<StateNode>,
    val transitions: List<Transition>,
    val current: String? = null,
    val window: GraphWindow,
    val merged: Int = 0,
    val notes: List<DegradationMarker> = emptyList(),
)

@Serializable
data class StatePath(
    val from: String,
    val to: String,
    val steps: List<Transition>,
    val found: Boolean,
)
