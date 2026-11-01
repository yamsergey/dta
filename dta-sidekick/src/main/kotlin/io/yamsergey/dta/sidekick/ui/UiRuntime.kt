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
    private var hooksReason: String? = "not started"

    /** For the network recorder: every stored entry, to link UI events to traffic by time. */
    fun observe(entry: NetworkEntry) {
        if (entry !is HttpEntry) return
        network.started(entry.id, entry.timeMs)
        if (entry.state != HttpState.PENDING) network.finished(entry.id, entry.timeMs + (entry.durationMs ?: 0))
    }

    @Synchronized
    fun start(context: Context, publish: (String, kotlinx.serialization.json.JsonElement?) -> Unit) {
        if (buffer != null) return
        val created = UiJournalBuffer(TimelineRuntime.start(publish)) { TimelineRuntime.scopeCause() }
        buffer = created
        TimelineRuntime.register(Sources.UI, ::sourceStatus)

        if (!HookAttach.attached) {
            hooksReason = HookAttach.reason ?: "hooks not attached"
            return
        }
        hooksReason = null
        try {
            val compose = if (ComposePresence().composePresent) ComposeSummaryWalker() else null
            val walker = WindowWalker(compose)
            val toastRecorder = ToastRecorder(created, walker, network::links)
            val windowRecorder = WindowRecorder(created, walker, network::links, toastRecorder::isToastView, ElementTracker(created), TimelineRuntime.facets)
            toastRecorder.install()?.let { Log.w(TAG, "toasts not recorded: $it") }
            windowRecorder.install()?.let { Log.w(TAG, "windows not recorded: $it") }
            if (windowRecorder.failure == null) windowRecorder.presentAtStart()
            toasts = toastRecorder
            windows = windowRecorder
        } catch (t: Throwable) {
            hooksReason = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "UI journal not started: $hooksReason")
        }
    }

    fun costs() = windows?.costs()

    /** The journal's id of the window [root] is the root view of, when it follows it. */
    fun windowIdOf(root: android.view.View): String? = windows?.windowIdOf(root)

    fun journalCapability(): UiCapability {
        fun state(failure: String?, recorder: Any?) = when {
            hooksReason != null -> SourceState.OFF to "hooks unavailable: $hooksReason"
            recorder == null -> SourceState.OFF to "not started"
            failure != null -> SourceState.OFF to failure
            else -> SourceState.ON to null
        }
        val (w, wr) = state(windows?.failure, windows)
        val (t, tr) = state(toasts?.failure, toasts)
        return UiCapability(
            sources = listOf(
                UiSourceState(UiSource.WINDOW, w, wr),
                UiSourceState(UiSource.TOAST, t, tr),
                UiSourceState(UiSource.ELEMENT, w, wr),
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
            off.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.source.name.lowercase()}: ${it.reason}" },
            "settle ${Settle.QUIET_MS} ms, max wait ${Settle.MAX_WAIT_MS} ms, summaries up to ${ContentSummaries.MAX_ELEMENTS} elements",
        )
    }

    fun capability(): CapabilityStatus {
        val off = journalCapability().sources.filter { it.state != SourceState.ON }
        return when {
            off.size == journalCapability().sources.size -> CapabilityStatus(NAME, CapabilityStatus.Status.UNAVAILABLE, off.joinToString("; ") { "${it.source.name.lowercase()}: ${it.reason}" })
            off.isNotEmpty() -> CapabilityStatus(NAME, CapabilityStatus.Status.DEGRADED, off.joinToString("; ") { "${it.source.name.lowercase()}: ${it.reason}" })
            else -> CapabilityStatus(NAME, CapabilityStatus.Status.AVAILABLE)
        }
    }
}
