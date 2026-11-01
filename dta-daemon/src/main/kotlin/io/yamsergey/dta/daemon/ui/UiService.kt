package io.yamsergey.dta.daemon.ui

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventsPage
import io.yamsergey.dta.protocol.ui.UiJournalRead
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
 * Follows one app's UI journal for the host (feature 015 US3, research R8): while the app is
 * connected it takes the sidekick's live `ui.event`s, catching up by seq whenever one does not
 * follow; when the link goes and the process no longer runs, the journal records its end.
 * Never dials from the background ([held]); a reader's call may ([connect]).
 */
class UiService(
    val journal: UiJournal,
    private val held: suspend () -> AppConnection?,
    private val connect: suspend () -> AppConnection,
    private val running: suspend () -> Boolean,
) {
    private val catchUpLock = Mutex()
    private val live = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1_000)

    /** Events as the journal takes them in, for `ui.watch`. */
    val events: Flow<UiEvent> = live.asSharedFlow()

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val app = held()
            if (app == null || !app.isOpen) {
                journal.connectionLost()
                if (!runCatching { running() }.getOrDefault(true)) journal.processEnded()
                delay(1_000)
                continue
            }
            val follow = launch {
                app.events
                    .onSubscription { runCatching { catchUp(app) } }
                    .filter { it.name == EVENT }
                    .collect { e ->
                        val event = e.data?.let { runCatching { DtaJson.decodeFromJsonElement(UiEvent.serializer(), it) }.getOrNull() } ?: return@collect
                        if (journal.pushed(event)) live.tryEmit(event) else runCatching { catchUp(app) }
                    }
            }
            while (isActive && app.isOpen) delay(500)
            follow.cancel()
            journal.connectionLost()
        }
    }

    /** The page a reader asked for, after catching up with the app when it can be reached. */
    suspend fun page(since: String?, limit: Int, sinceMs: Long?, kinds: Set<String>): UiEventsPage {
        val app = held() ?: runCatching { connect() }.getOrNull()
        if (app != null && app.isOpen) {
            runCatching { catchUp(app) }
        } else {
            // Not reachable: if the process is gone, the reader learns it now, not on the next poll.
            journal.connectionLost()
            if (!runCatching { running() }.getOrDefault(true)) journal.processEnded()
        }
        if (since == END) return journal.page(journal.end(), limit, sinceMs, kinds)
        return journal.page(since, limit, sinceMs, kinds)
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

    private suspend fun read(app: AppConnection, afterSeq: Long): UiJournalRead =
        DtaJson.decodeFromJsonElement(UiJournalRead.serializer(), app.request(READ, buildJsonObject { put("afterSeq", afterSeq); put("limit", PAGE) }))

    companion object {
        const val EVENT = "ui.event"
        const val READ = "ui_journal_read"
        const val PAGE = 200

        /** `since` value: start after everything kept (an agent's first call). */
        const val END = "end"
    }
}
