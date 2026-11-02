package io.yamsergey.dta.sidekick.ui

import android.content.Context
import android.util.Log
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.protocol.ui.UiCapability
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.UiSourceState
import io.yamsergey.dta.sidekick.hooks.HookAttach
import io.yamsergey.dta.sidekick.timeline.TimelineRuntime
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.sidekick.capability.Reasons
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence

/**
 * Process-wide journal of short-lived UI (feature 015): the ring, the window and toast
 * recorders, the network links. Started right after the hook agent attaches, like network
 * capture: recording is observation and runs without a client. Never throws into the app.
 */
object UiRuntime {
    const val NAME = "ui"
    private const val TAG = "DtaUi"

    @Volatile
    var buffer: UiJournalBuffer? = null
        private set

    val network = RecentNetwork()

    private var windows: WindowRecorder? = null
    private var toasts: ToastRecorder? = null
    private var hooksReason: String? = null
    private var startFailure: String? = null

    /** For the network recorder: every stored entry, to link UI events to traffic by time. */
    fun observe(entry: NetworkEntry) {
        if (entry !is HttpEntry) return
        network.started(entry.id, entry.timeMs)
        if (entry.state != HttpState.PENDING) network.finished(entry.id, entry.timeMs + (entry.durationMs ?: 0))
    }

    @Synchronized
    fun start(context: Context, publish: (String, kotlinx.serialization.json.JsonElement?) -> Unit) {
        if (buffer != null) return
        val timeline = TimelineRuntime.start(publish)
        val created = UiJournalBuffer(timeline) { TimelineRuntime.scopeCause() }
        buffer = created
        TimelineRuntime.register(Sources.UI, ::sourceStatus)
        TimelineRuntime.register(Sources.SCREEN) {
            val ui = sourceStatus()
            SourceStatus(Sources.SCREEN, ui.state, ui.reason, "the structure of the top activity's window, from the journal's summaries; texts and values do not count")
        }

        if (!HookAttach.attached) {
            hooksReason = HookAttach.reason ?: Reasons.HOOKS_STARTING
            return
        }
        hooksReason = null
        try {
            val compose = if (ComposePresence().composePresent) ComposeSummaryWalker() else null
            val walker = WindowWalker(compose)
            val toastRecorder = ToastRecorder(created, walker, network::links)
            val screen = ScreenSource(timeline, TimelineRuntime.facets) { TimelineRuntime.scopeCause() }
            val windowRecorder = WindowRecorder(created, walker, network::links, toastRecorder::isToastView, ElementTracker(created), TimelineRuntime.facets, screen::settled)
            toastRecorder.install()?.let { Log.w(TAG, "toasts not recorded: $it") }
            windowRecorder.install()?.let { Log.w(TAG, "windows not recorded: $it") }
            if (windowRecorder.failure == null) windowRecorder.presentAtStart()
            toasts = toastRecorder
            windows = windowRecorder
        } catch (t: Throwable) {
            startFailure = Reasons.SOURCE_FAILED
            Log.w(TAG, "UI journal not started", t)
        }
    }

    fun costs() = windows?.costs()

    /** The journal's id of the window [root] is the root view of, when it follows it. */
    fun windowIdOf(root: android.view.View): String? = windows?.windowIdOf(root)

    fun journalCapability(): UiCapability {
        // Feature 019: each source says what it does not record and why; hooks-off is told once,
        // by the capability, so the sources only point to it.
        fun state(lost: String, failure: String?, recorder: Any?) = when {
            hooksReason != null -> SourceState.OFF to Reasons.partOffBecauseHooks(lost)
            startFailure != null -> SourceState.OFF to "$lost: $startFailure"
            recorder == null -> SourceState.OFF to Reasons.JOURNAL_STARTING
            failure != null -> SourceState.OFF to failure
            else -> SourceState.ON to null
        }
        val (w, wr) = state(Reasons.WINDOWS, windows?.failure, windows)
        val (t, tr) = state(Reasons.TOASTS, toasts?.failure, toasts)
        val (e, er) = state(Reasons.ELEMENTS, windows?.failure?.let { "${Reasons.ELEMENTS} (they come with windows)" }, windows)
        return UiCapability(
            sources = listOf(
                UiSourceState(UiSource.WINDOW, w, wr),
                UiSourceState(UiSource.TOAST, t, tr),
                UiSourceState(UiSource.ELEMENT, e, er),
            ),
            settleMs = Settle.QUIET_MS,
            maxWaitMs = Settle.MAX_WAIT_MS,
            bufferSize = io.yamsergey.dta.sidekick.timeline.TimelineBuffer.CAPACITY,
            summaryBound = ContentSummaries.MAX_ELEMENTS,
        )
    }

    /** The `ui` source's line in the timeline (016): on when windows and toasts are both recorded. */
    fun sourceStatus(): SourceStatus {
        val journal = journalCapability()
        val off = journal.sources.filter { it.state != SourceState.ON }
        val state = when {
            off.size == journal.sources.size -> SourceState.OFF
            off.isNotEmpty() -> SourceState.DEGRADED
            else -> SourceState.ON
        }
        return SourceStatus(
            Sources.UI, state,
            off.takeIf { it.isNotEmpty() }?.let(::offReason),
            "settle ${Settle.QUIET_MS} ms, max wait ${Settle.MAX_WAIT_MS} ms, summaries up to ${ContentSummaries.MAX_ELEMENTS} elements",
        )
    }

    fun capability(): CapabilityStatus {
        val off = journalCapability().sources.filter { it.state != SourceState.ON }
        return when {
            off.size == journalCapability().sources.size -> CapabilityStatus(NAME, CapabilityStatus.Status.UNAVAILABLE, offReason(off), detail = HookAttach.detail.takeIf { hooksReason != null })
            off.isNotEmpty() -> CapabilityStatus(NAME, CapabilityStatus.Status.DEGRADED, offReason(off))
            else -> CapabilityStatus(NAME, CapabilityStatus.Status.AVAILABLE)
        }
    }

    /** Hooks-off is said once, with why; otherwise each source's own reason, without repeats. */
    private fun offReason(off: List<UiSourceState>): String {
        hooksReason?.let { why ->
            return Reasons.offBecauseHooks("window opens and closes, toasts and element changes are not recorded", why)
        }
        return off.mapNotNull { it.reason }.distinct().joinToString("; ")
    }
}
