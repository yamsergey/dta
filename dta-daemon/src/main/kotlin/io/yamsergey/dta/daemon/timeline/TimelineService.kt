package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.daemon.ui.HostWindowWatch
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelinePage
import io.yamsergey.dta.protocol.timeline.TimelineRead
import io.yamsergey.dta.protocol.ui.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Follows one app's timeline for the host (features 015/016): while the app is connected it
 * takes the sidekick's live `timeline.event`s, catching up by seq whenever one does not
 * follow; when the link goes and the process no longer runs, the timeline records its end.
 * Never dials from the background ([held]); a reader's call may ([connect]).
 */
class TimelineService(
    val journal: Timeline,
    private val held: suspend () -> AppConnection?,
    private val connect: suspend () -> AppConnection,
    private val running: suspend () -> Boolean,
    /** Windows that are not the app's (US5); null when the host cannot sample them. */
    val windows: HostWindowWatch? = null,
) {
    private val catchUpLock = Mutex()
    /** A reader just connected to the app: the follower subscribes now instead of at its next look. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val live = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 1_000)

    /** Events as the timeline takes them in, for `timeline.watch`. */
    val events: Flow<TimelineEvent> = live.asSharedFlow()

    fun start(scope: CoroutineScope) {
        windows?.start(scope)
        follow(scope)
    }

    private fun follow(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val app = held()
            if (app == null || !app.isOpen) {
                journal.connectionLost()
                if (!runCatching { running() }.getOrDefault(true)) journal.processEnded()
                kotlinx.coroutines.withTimeoutOrNull(1_000) { wake.receive() }
                continue
            }
            val follow = launch {
                app.events
                    .onSubscription { runCatching { catchUp(app) } }
                    .filter { it.name == EVENT }
                    .collect { e ->
                        val event = e.data?.let { runCatching { DtaJson.decodeFromJsonElement(TimelineEvent.serializer(), it) }.getOrNull() } ?: return@collect
                        if (journal.pushed(event)) live.tryEmit(event) else runCatching { catchUp(app) }
                    }
            }
            while (isActive && app.isOpen) delay(500)
            follow.cancel()
            journal.connectionLost()
        }
    }

    /** The page a reader asked for, after catching up with the app when it can be reached. */
    suspend fun page(since: String?, limit: Int, sinceMs: Long?, sources: Set<String>, kinds: Set<String>): TimelinePage {
        windows?.touch()
        refresh()
        val at = if (since == END) journal.end() else since
        val page = journal.page(at, limit, sinceMs, sources, kinds)
        return page.copy(sources = sources(page.sources))
    }

    /** Catches up with the app when it can be reached, so a read is current. */
    suspend fun refresh() {
        val app = held() ?: runCatching { connect() }.getOrNull()
        if (app != null && app.isOpen) {
            wake.trySend(Unit)
            runCatching { catchUp(app) }
        } else {
            // Not reachable: if the process is gone, the reader learns it now, not on the next poll.
            journal.connectionLost()
            if (!runCatching { running() }.getOrDefault(true)) journal.processEnded()
        }
    }

    /** The app's sources, with the host's sampling of windows that are not the app's folded into `ui`. */
    fun sources(app: List<SourceStatus> = journal.sources): List<SourceStatus> {
        val host = if (windows == null) {
            "windows that are not the app's: the host cannot read the device's window list"
        } else {
            "windows that are not the app's: sampled every ${windows.intervalMs} ms while the timeline is read; shorter ones may be missed"
        }
        val ui = app.firstOrNull { it.name == io.yamsergey.dta.protocol.timeline.Sources.UI }
        val merged = ui?.copy(detail = listOfNotNull(ui.detail, host).joinToString("; "))
            ?: SourceStatus(io.yamsergey.dta.protocol.timeline.Sources.UI, if (windows == null) SourceState.OFF else SourceState.DEGRADED, "the app's journal is not connected", host)
        return listOf(merged) + app.filter { it.name != merged.name }
    }

    private suspend fun catchUp(app: AppConnection) = catchUpLock.withLock {
        while (true) {
            var read = read(app, journal.afterSeq)
            // Another process than the one followed: its seqs start again, so read it from its start.
            if (read.epoch != journal.followedEpoch && journal.afterSeq > 0) read = read(app, 0)
            val before = if (read.epoch == journal.followedEpoch) journal.afterSeq else 0L
            journal.ingest(read)
            read.events.filter { it.seq > before }.forEach { live.tryEmit(it) }
            if (read.events.size < PAGE) break
        }
    }

    private suspend fun read(app: AppConnection, afterSeq: Long): TimelineRead =
        DtaJson.decodeFromJsonElement(TimelineRead.serializer(), app.request(READ, buildJsonObject { put("afterSeq", afterSeq); put("limit", PAGE) }))

    companion object {
        const val EVENT = "timeline.event"
        const val READ = "timeline_read"
        const val PAGE = 200

        /** `since` value: start after everything kept (an agent's first call). */
        const val END = "end"
    }
}
