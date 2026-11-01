package io.yamsergey.dta.protocol.ui

/** Feature 015: the journal of short-lived UI. Its event is the timeline's event shape (data-model). */

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UiEventKind {
    @SerialName("window-appeared") WINDOW_APPEARED,
    @SerialName("window-settled") WINDOW_SETTLED,
    @SerialName("window-disappeared") WINDOW_DISAPPEARED,
    @SerialName("window-present-at-start") WINDOW_PRESENT_AT_START,
    @SerialName("toast") TOAST,
    @SerialName("element-appeared") ELEMENT_APPEARED,
    @SerialName("element-disappeared") ELEMENT_DISAPPEARED,
    @SerialName("gap") GAP,
    @SerialName("process-ended") PROCESS_ENDED,
}

@Serializable
enum class UiSource {
    @SerialName("window") WINDOW,
    @SerialName("toast") TOAST,
    @SerialName("element") ELEMENT,
    /** Sampled by the host from the device's window list (US5). */
    @SerialName("screen-window") SCREEN_WINDOW,
    @SerialName("journal") JOURNAL,
}

@Serializable
enum class WindowKind {
    @SerialName("activity") ACTIVITY,
    @SerialName("dialog") DIALOG,
    @SerialName("popup") POPUP,
    @SerialName("bottom-sheet") BOTTOM_SHEET,
    @SerialName("toast") TOAST,
    @SerialName("keyboard") KEYBOARD,
    @SerialName("system") SYSTEM,
    @SerialName("other-app") OTHER_APP,
    @SerialName("other") OTHER,
}

/** The window an event is about or happens in. */
@Serializable
data class WindowRef(
    /** Stable within the process; for host-sampled windows the window's name. */
    val windowId: String,
    val kind: WindowKind,
    val title: String? = null,
    /** The owner activity's class, or the owning package for a window that is not the app's. */
    val owner: String? = null,
    /** Window type from the layout params. */
    val type: Int? = null,
    val secure: Boolean = false,
    val app: Boolean = true,
    /** An activity window replacing one of the same activity (recreation). */
    val recreatedFrom: String? = null,
)

/** An element inside a window (US4). */
@Serializable
data class ElementRef(
    val key: String,
    val role: String? = null,
    val parent: String? = null,
)

@Serializable
data class SummaryElement(
    /** button, text, input, heading, image, progress, container. */
    val role: String,
    val text: String? = null,
    val description: String? = null,
    /** Resource name or test tag. */
    val name: String? = null,
    val depth: Int = 0,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    /** A password input: its value is never recorded. */
    val password: Boolean = false,
    /** Identity across summaries (US4); not shown to readers. */
    val key: String? = null,
)

/** The bounded structure-and-text description of a window or element at one moment (FR-003, FR-004). */
@Serializable
data class ContentSummary(
    val elements: List<SummaryElement> = emptyList(),
    val omitted: Int = 0,
    val textsWithheld: Boolean = false,
    val settled: Boolean = true,
    val takenMonoMs: Long = 0,
)

@Serializable
enum class LinkRelation {
    @SerialName("in-flight") IN_FLIGHT,
    @SerialName("finished-before") FINISHED_BEFORE,
}

/** A network entry related to an event by time only, never stated as its cause (FR-010). */
@Serializable
data class EventLink(
    val kind: String,
    val id: String,
    val relation: LinkRelation,
    val relationBasis: String = "time",
)

@Serializable
data class UiEvent(
    /** `<epoch>:<seq>`; the epoch is the app process's start. */
    val id: String,
    val seq: Long,
    val monoMs: Long,
    val wallMs: Long,
    val kind: UiEventKind,
    val source: UiSource,
    val window: WindowRef? = null,
    val element: ElementRef? = null,
    val content: ContentSummary? = null,
    /** Toast text; for element events the element's text. */
    val text: String? = null,
    /** Lifetime for a disappearance; requested duration for a toast. */
    val durationMs: Long? = null,
    /** The matching appearance for a settle or a disappearance. */
    val appearedId: String? = null,
    /** For a gap: events lost by count, or the interval in which events may be missing. */
    val gapCount: Int? = null,
    val gapFromMs: Long? = null,
    val gapToMs: Long? = null,
    val completeness: List<DegradationMarker> = emptyList(),
    val links: List<EventLink> = emptyList(),
)

@Serializable
enum class SourceState {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("degraded") DEGRADED,
}

@Serializable
data class UiSourceState(
    val source: UiSource,
    val state: SourceState,
    val reason: String? = null,
)

/** What the journal records and why not (FR-018). */
@Serializable
data class UiCapability(
    val sources: List<UiSourceState> = emptyList(),
    val settleMs: Long? = null,
    val maxWaitMs: Long? = null,
    /** Host window sampling interval (US5), when sampling. */
    val samplingMs: Long? = null,
    val bufferSize: Int? = null,
    val hostBound: Int? = null,
    val summaryBound: Int? = null,
)

/** One read of the journal (`ui.events`). */
@Serializable
data class UiEventsPage(
    val events: List<UiEvent>,
    /** Pass as `since` to continue after the last returned event. */
    val next: String,
    val more: Boolean = false,
    val capability: UiCapability = UiCapability(),
)

/** The sidekick's answer to `ui.journal.read` (contract). */
@Serializable
data class UiJournalRead(
    val epoch: Long,
    val events: List<UiEvent>,
    /** Events lost to the ring since the requested seq. */
    val dropped: Int = 0,
    val lastSeq: Long = 0,
    val capability: UiCapability = UiCapability(),
)
