package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.connection.ConnectionLostException
import io.yamsergey.dta.daemon.connection.RequestTimeoutException
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.daemon.store.ArchivingStoppedException
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.Epoch
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.store.InstallationInfo
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.BodyRangeResult
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.network.JournalIdentityResult
import io.yamsergey.dta.protocol.network.JournalPageResult
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.SearchPage
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.coroutines.async
import io.yamsergey.dta.daemon.store.StoreEntrySource
import io.yamsergey.dta.protocol.network.EntrySourceProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One app's network history on the host (feature 011 US2): caught up from the app's journal
 * before every answer, kept in the [HostStore] (the live epoch) and decoded in memory, and
 * answered with the same [NetworkHistoryView] the app would use — so the answers are the app's.
 * An app without storage answers itself, as before (memory-only fallback, FR-011).
 */
class HostIndex(
    private val store: HostStore,
    private val device: DeviceIdentity,
    private val packageName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The app's protobuf decoding (its schemas and mappings), for search in decoded bodies (US6). */
    private val decoding: () -> io.yamsergey.dta.daemon.network.proto.BodyDecoding = { io.yamsergey.dta.daemon.network.proto.BodyDecoding.NONE },
    /** Feature 051: a text message's envelope by the app's format rules, for `type:` and `topic:`. */
    private val readEnvelope: (io.yamsergey.dta.protocol.network.WsMessageEntry, String) -> io.yamsergey.dta.protocol.network.Envelope? =
        { _, text -> io.yamsergey.dta.protocol.network.WsFormats.detect(text) },
    /** Feature 070: names the app's schemas now; decoded bodies' words are written again when it changes. */
    private val wordsFingerprint: () -> String = { "" },
    /** Feature 071 (research R6): where answers that read bodies walk, apart from the other work; null: on the caller. */
    private val searches: SearchRunner? = null,
    private val connection: suspend () -> AppConnection,
) {
    /** Feature 070 (research R5): the app's word index, written in the background ([indexWords]). */
    private val words = WordIndexer(store, device.key, packageName, ::wordEpochs, wordsFingerprint, ::indexText)

    /** Every epoch of the app, archived and live: what the indexer writes words for. */
    private fun wordEpochs(): List<String> =
        store.epochs(device.key, packageName, io.yamsergey.dta.protocol.network.ArchiveScope(all = true)).map { it.id } +
            listOfNotNull(store.liveEpoch(device.key, packageName)?.id)

    /** An entry's words as the answers read it: bodies held here, binary ones as the app's schemas decode them. */
    private fun indexText(epochId: String, entry: NetworkEntry): io.yamsergey.dta.protocol.network.IndexText.Result =
        io.yamsergey.dta.protocol.network.IndexText.of(
            entry, { e, p -> hostBodyBytes(epochId, e, p) },
            { e, p -> decodedJson(e, p, epochId) { id -> store.entry(epochId, id) ?: store.epochOf(wordEpochs(), id)?.let { store.entry(it, id) } } },
        )

    /** Feature 070: writes one batch of pending words; how many were written (0: none waits). */
    fun indexWords(): Int = words.step()

    /** Feature 070: entries whose words are not written yet. */
    fun wordsPending(): Int = words.pending()

    /** Journal records per request, and requests per answer before the rest is left to the next one. */
    var pageSize = 500
    var catchUpBudgetPages = 40

    /**
     * Set once the feed reads the journal in the background ([openFeed]): from then on a query reads
     * one page at most before it answers, saying how far behind, instead of catching up itself
     * (found in the robot IDE, 070: a search waited ~15 s while 600k entries were caught up).
     */
    @Volatile
    private var readsInBackground = false

    /** The daemon reads this app's journal in the background from the start: a query never waits for a whole catch-up. */
    fun readInBackground() { readsInBackground = true }

    /** True while [openFeed] reads pages: a query then reads none, the catch-up beside it brings them. */
    @Volatile
    private var catchingUpInBackground = false

    private fun queryPages(): Int = when {
        catchingUpInBackground -> 0
        readsInBackground -> 1
        else -> catchUpBudgetPages
    }

    /** Feature 068: records still to read while a catch-up runs (read without the mutex by [history]). */
    @Volatile
    private var catchingUpNow = 0L
    /** Records the device said were left after the last page read. */
    private var behindNow = 0L

    /** Feature 068: (when, how far behind) of each batch applied in the last [LAG_WINDOW_MS]. */
    private val feedLags = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, Long>>()

    /** The most any batch of the last [LAG_WINDOW_MS] was behind the app, or null when none came. */
    private fun feedLag(): Long? {
        val since = clock() - LAG_WINDOW_MS
        while (feedLags.peekFirst()?.let { it.first < since } == true) feedLags.pollFirst()
        return feedLags.filter { it.first >= since }.maxOfOrNull { it.second }
    }
    /**
     * How long an answer copies the bodies it reads before it is given: the rest is read by its
     * preview and counted (coverage.bodiesPending), and the copy worker goes on. A large backlog
     * would otherwise hold the first search for minutes.
     */
    var searchCopyMs = SEARCH_COPY_MS

    private val mutex = Mutex()
    /** Feature 068: catch-ups an answer started and did not wait for. */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var epoch: Epoch? = null
    @Volatile private var context: HistoryContext? = null
    private var lag = 0L
    /** Entries the device cleared that the host never read, known when the clear went through the daemon. */
    private var neverReceived = 0
    /** Feature 066: entries the device holds (its identity), when the last acknowledgement went, why none goes. */
    private var deviceEntries = 0
    private var lastAckMs: Long? = null
    private var archiveHeld: String? = null

    /**
     * Feature 069 (research R1): the live epoch as one answer reads it, from disk (each answer opens
     * one read); feature 070: narrowed by the query's filter and its words' need.
     */
    private fun liveView(epochId: String?, ctx: HistoryContext, run: io.yamsergey.dta.daemon.store.RunBound? = null, stop: io.yamsergey.dta.protocol.network.PatternStop = io.yamsergey.dta.protocol.network.PatternStop.NONE) = NetworkHistoryView.over(
        store.sources({ listOfNotNull(epochId) }, { run }), { ctx },
        { e, p -> hostBodyBytes(epochId, e, p) }, { entry, part -> decodedJson(entry, part, epochId) { socketOf(epochId, it) } }, readEnvelope, { stop },
    )

    /** Feature 071: the app's current run of [epochId] as the default scope reads it (nothing while none was recorded). */
    private fun runBound(epochId: String?, ctx: HistoryContext): io.yamsergey.dta.daemon.store.RunBound? = epochId?.let { e ->
        val first = store.runs(e).firstOrNull { it.runId == ctx.runId }?.firstSeq ?: Long.MAX_VALUE
        io.yamsergey.dta.daemon.store.RunBound(e, ctx.runId, first)
    }

    /** Feature 071: the archived epochs (in the order they were opened) and then the live one. */
    private fun wholeIds(epochId: String?): List<String> =
        store.epochs(device.key, packageName, io.yamsergey.dta.protocol.network.ArchiveScope(all = true)).map { it.id } + listOfNotNull(epochId)

    /** Feature 071 (FR-002): the whole history this computer keeps for the app, with the live context. */
    private fun wholeView(epochId: String?, ctx: HistoryContext, stop: io.yamsergey.dta.protocol.network.PatternStop = io.yamsergey.dta.protocol.network.PatternStop.NONE): NetworkHistoryView {
        val ids = wholeIds(epochId)
        fun epochOf(e: NetworkEntry): String? = if (epochId != null && store.entry(epochId, e.id) != null) epochId else store.epochOf(ids, e.id)
        fun byId(id: String): NetworkEntry? = socketOf(epochId, id) ?: store.epochOf(ids, id)?.let { store.entry(it, id) }
        return NetworkHistoryView.over(
            store.sources({ ids }), { ctx },
            { e, p -> if (NetworkHistoryView.bodyOf(e, p)?.wholeText() != null) hostBodyBytes(null, e, p) else hostBodyBytes(epochOf(e), e, p) },
            { entry, part -> decodedJson(entry, part, epochOf(entry), ::byId) },
            readEnvelope, { stop },
        )
    }

    /** Feature 071: the view of [query]'s scope over the live history: its current run, or (switch on) everything. */
    private fun scopedView(query: NetworkQuery, epochId: String?, ctx: HistoryContext, stop: io.yamsergey.dta.protocol.network.PatternStop = io.yamsergey.dta.protocol.network.PatternStop.NONE): NetworkHistoryView =
        if (query.archive) wholeView(epochId, ctx, stop) else liveView(epochId, ctx, runBound(epochId, ctx), stop)

    /** Feature 071 (FR-004, FR-014): what an answer of [query] covers, from kept counters. */
    private fun scopeOf(query: NetworkQuery, epochId: String?, ctx: HistoryContext): io.yamsergey.dta.protocol.network.HistoryScope {
        val runs = epochId?.let { store.runs(it) }.orEmpty()
        val told = ctx.coverage.runs.associateBy { it.runId }
        fun shown(runId: String, entries: Long, firstMs: Long?) = told[runId].let { r ->
            io.yamsergey.dta.protocol.network.ScopeRun(runId, entries, r?.startedAtMs ?: firstMs, r?.endedAtMs, r?.ended)
        }
        val runsShown = runs.map { shown(it.runId, (it.entries - it.links).toLong(), it.firstMs) }
        val current = runsShown.firstOrNull { it.runId == ctx.runId } ?: ctx.runId.takeIf { it.isNotEmpty() }?.let { shown(it, 0, null) }
        val startedNow = current?.startedMs
        val previous = runsShown.firstOrNull { it.runId != ctx.runId && (startedNow == null || (it.startedMs ?: 0) <= startedNow) }
        val archived = store.epochs(device.key, packageName, io.yamsergey.dta.protocol.network.ArchiveScope(all = true))
            .map { io.yamsergey.dta.protocol.network.ScopeEpoch(it.id, store.networkCount(it.id).toLong(), it.closedMs, it.reason) }
        val all = (epochId?.let { store.networkCount(it) } ?: 0) + archived.sumOf { it.entries }
        return if (query.archive) {
            io.yamsergey.dta.protocol.network.HistoryScope(true, all, current, previous = previous, runs = runsShown.ifEmpty { null }, epochs = archived.asReversed().ifEmpty { null })
        } else {
            val mine = current?.entries ?: 0
            io.yamsergey.dta.protocol.network.HistoryScope(false, mine, current, previous = previous, outside = (all - mine).takeIf { it > 0 }, runAllIgnored = query.runAllIgnored())
        }
    }

    /** Feature 071: entries of the whole history that are archived, marked with their epoch (011 FR-016). */
    private fun marks(ids: Collection<String>): Map<String, io.yamsergey.dta.protocol.network.ArchivedMark>? {
        if (ids.isEmpty()) return null
        val archived = store.epochs(device.key, packageName, io.yamsergey.dta.protocol.network.ArchiveScope(all = true))
        if (archived.isEmpty()) return null
        val byId = archived.associateBy { it.id }
        val live = epoch?.id
        return ids.mapNotNull { id -> if (live != null && store.entry(live, id) != null) null else store.epochOf(archived.map { it.id }, id)?.let { id to mark(byId.getValue(it)) } }.toMap().ifEmpty { null }
    }

    /** A socket of the live epoch: kept once seen (few per run), else read from disk. */
    private fun socketOf(epochId: String?, id: String): WsConnectionEntry? = liveSockets[id]
        ?: (epochId?.let { store.entry(it, id) } as? WsConnectionEntry)?.also { liveSockets.putIfAbsent(id, it) }

    private fun emptyContext() = HistoryContext("", io.yamsergey.dta.protocol.network.Coverage(searched = 0, stored = 0, persisted = true),
        io.yamsergey.dta.protocol.rpc.CapabilityStatus(NetworkHistoryView.CAPABILITY, io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status.AVAILABLE))

    /**
     * Feature 067: bodies of finished live entries not copied yet, kept as records arrive and copies
     * finish (it was one store lookup per entry and part on every answer). Copies run outside the mutex.
     */
    /** Feature 067: an entry whose body was just copied here — a live view decides it again (it was decided by its preview). */
    val copied = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1024, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Bodies being copied now: an answer and the copy worker never append to one partial file at once. */
    private val copying = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, BodyPart>>()

    /** Feature 067: bumped whenever live entries are dropped (a new epoch, a rollback). */
    var removals = 0L
        private set

    /** Feature 067: socket connections of the live history, read by live decisions outside the mutex. */
    private val liveSockets = java.util.concurrent.ConcurrentHashMap<String, WsConnectionEntry>()

    /** Feature 067: what each live view (a plugin tab, a CLI watch) was shown. */
    internal val views = LiveViews()

    /**
     * Feature 067: a different query of a view abandons its running pass. 070: the same query (its
     * next page, a relist) does not: each of them abandoned the other's pass, and the tab said so.
     */
    private val viewQueries = java.util.concurrent.ConcurrentHashMap<String, NetworkQuery>()

    private fun stopperFor(view: String, query: NetworkQuery): () -> Unit {
        val mine = views.key(query)
        viewQueries[view] = mine
        return { if (viewQueries[view] != mine) throw SupersededException(view) }
    }

    /** A view's pass replaced by a newer query of the same view; the answer is not needed any more. */
    class SupersededException(view: String) : kotlinx.coroutines.CancellationException("superseded by a newer query of view $view")

    /** Feature 067: the list for a live [view]; what it shows is remembered for its live decisions. */
    suspend fun list(query: NetworkQuery, view: String): NetworkPage {
        val stop = stopperFor(view, query)
        return list(query) { v -> v.list(query, stop = stop).also { page -> views.shown(view, query, page.entries.map { it.id }) } }
    }

    /** Feature 067: the search for a live [view]. */
    suspend fun search(query: NetworkQuery, view: String): SearchPage {
        val stop = stopperFor("$view#search", query)
        return search(query) { v -> v.search(query, stop = stop) }
    }

    /**
     * Feature 067: [query]'s decision for one live entry (a subscription's event), as a pass would make it
     * now — its bodies from this computer's copy, else their previews — kept in [view]'s current match
     * set. Second: whether the entry was in that set before. Runs outside the mutex.
     */
    fun decide(view: String, query: NetworkQuery, entry: NetworkEntry, socketUrl: (String) -> String?) = decide(view, query, entry, io.yamsergey.dta.protocol.network.PatternStop.NONE, socketUrl)

    /** As [decide]; [stop] ends a decision whose pattern runs long (its subscription went away, 071). */
    fun decide(view: String, query: NetworkQuery, entry: NetworkEntry, stop: io.yamsergey.dta.protocol.network.PatternStop, socketUrl: (String) -> String?): Pair<io.yamsergey.dta.protocol.network.EntryDecision, Boolean> {
        (entry as? WsConnectionEntry)?.let { liveSockets.putIfAbsent(it.id, it) }
        val epochId = epoch?.id
        val ctx = context ?: emptyContext()
        fun socket(id: String): NetworkEntry? = socketOf(epochId, id)
            ?: socketUrl(id)?.let { WsConnectionEntry(id, entry.runId, 0, entry.library, url = it, state = WsState.OPEN) }
        val v = NetworkHistoryView({ emptyList() }, ::socket, { ctx }, { e, p -> hostBodyBytes(epochId, e, p) },
            { e, p -> decodedJson(e, p, epochId, ::socket) }, readEnvelope, { stop })
        // Feature 071: without the switch only the current run is in the view; a late update of an earlier run is not.
        val d = if (!query.archive && query.archiveScope == null && entry.runId != ctx.runId) io.yamsergey.dta.protocol.network.EntryDecision.NO else v.decide(query, entry)
        return d to views.decided(view, query, entry.id, d.match)
    }

    /** Feature 067: a live entry as this computer holds it now (after its body was copied). */
    suspend fun liveEntry(id: String): NetworkEntry? = epoch?.let { store.entry(it.id, id) }

    private fun resetLive() {
        liveSockets.clear()
        removals++
    }

    /**
     * After [records] went into the store ([created]: the ids that were new there): watchers are told,
     * and each finished entry's bodies are noted as still to copy (feature 069: on disk, not in
     * memory), but for one its preview holds whole: the record is its copy (070 E13).
     */
    private fun putLive(epochId: String, records: List<NetworkEntry>, created: Set<String>) {
        val told = HashSet<String>()
        for (r in records) {
            emitApplied(r, r.id in created && told.add(r.id))
            if (r is WsConnectionEntry) liveSockets[r.id] = r
            val pendingParts = ArrayList<Pair<BodyPart, Long>>()
            if (!inFlight(r)) for (part in BodyPart.entries) {
                val info = NetworkHistoryView.bodyOf(r, part) ?: continue
                // 070 E13: a body its preview holds whole (a small text message) is archived in its record: no file,
                // no hash, no rows (they held the catch-up: ~39k messages of 5 min, each written as a file).
                if (info.storedBytes <= 0 || info.wholeText() != null || store.bodyRef(epochId, r.id, part) != null) continue
                pendingParts += part to info.storedBytes
            }
            store.setPending(epochId, r.id, pendingParts)
        }
    }

    /**
     * Feature 069: a pass over [query]'s scope (071) on one read of the disk (one transaction), outside
     * the mutex. 071: one that reads bodies walks on the search pool; any pass stops when its caller is
     * cancelled, also inside a match.
     */
    private suspend fun <T> livePass(query: NetworkQuery, epochId: String?, ctx: HistoryContext, pass: (NetworkHistoryView) -> T): T {
        val runner = searches
        if (runner != null && needsBodies(query)) return runner.run { stop -> pass(scopedView(query, epochId, ctx, stop)) }
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        return pass(scopedView(query, epochId, ctx, io.yamsergey.dta.protocol.network.PatternStop { job?.isActive == false }))
    }

    suspend fun list(query: NetworkQuery): NetworkPage = list(query, pass = { v -> v.list(query) })

    /**
     * The live list through [pass] (feature 067: the whole history, or a view's candidates), run on a
     * snapshot outside the mutex; the coverage this computer adds is read under it, before.
     */
    internal suspend fun list(query: NetworkQuery, pass: (NetworkHistoryView) -> NetworkPage): NetworkPage {
        query.archiveScope?.let { return archived(query, it) }
        // One hold of the mutex when there are no bodies to copy: beside a catch-up, each hold waits for a page.
        var snapshot: Pair<Triple<String?, HistoryContext, Long>, Extra>? = null
        val toCopy = mutex.withLock {
            val app = connection()
            if (!sync(app)) return appAnswers(app, query)
            (if (needsBodies(query)) pending() else emptyList()).also { if (it.isEmpty()) snapshot = liveSnapshot() }
        }
        // Copied outside the mutex: opening an entry does not wait for the bodies a search needs.
        copyAll(toCopy)
        val (at, extra) = snapshot ?: mutex.withLock { liveSnapshot() }
        val page = livePass(query, at.first, at.second, pass)
        return page.copy(
            scope = scopeOf(query, at.first, at.second),
            archived = if (query.archive) marks(page.entries.map { it.id }) else page.archived,
            coverage = page.coverage.copy(
                hostLagRecords = extra.hostLagRecords,
                bodiesPending = extra.bodiesPending,
                bodiesNotArchived = extra.bodiesNotArchived,
                archivedEpochs = extra.archivedEpochs.takeUnless { query.archive },
                archivingStopped = extra.archivingStopped,
                hostOnly = extra.hostOnly,
                archivedByOther = page.coverage.archivedOnly?.filterKeys { it != store.hostId }?.values?.sum()?.takeIf { it > 0 },
                archiveHeld = extra.archiveHeld,
            ),
        )
    }

    /** Under the mutex: the live epoch and context, and the coverage this computer adds to an answer. */
    private fun liveSnapshot() = Triple(epoch?.id, context!!, removals) to Extra(
        hostLagRecords = lag.takeIf { it > 0 },
        bodiesPending = epoch?.let { store.pendingBodies(it.id).size }?.takeIf { it > 0 },
        bodiesNotArchived = epoch?.let { store.countNotArchived(it.id) }?.takeIf { it > 0 },
        archivedEpochs = store.archivedCount(device.key, packageName).takeIf { it > 0 },
        archivingStopped = store.archivingStopped,
        hostOnly = (liveCount() - deviceEntries).toLong().takeIf { lag == 0L && it > 0 },
        archiveHeld = archiveHeld,
    )

    private class Extra(
        val hostLagRecords: Long?, val bodiesPending: Int?, val bodiesNotArchived: Int?, val archivedEpochs: Int?,
        val archivingStopped: String?, val hostOnly: Long?, val archiveHeld: String?,
    )

    /**
     * Feature 066 (contracts/operations.md): how much history this app has on the device and on
     * this computer. The device's part is null, said why, when the app cannot be reached or is
     * too old to report it.
     */
    suspend fun history(): io.yamsergey.dta.protocol.network.HistorySummary {
        // Feature 068: while this computer catches up (the mutex held for a long read), say how far at once.
        if (mutex.isLocked) return historyMeanwhile()
        // The full answer reads the journal first; asked first after a gap, that is the whole catch-up
        // (found in the robot IDE: the plugin learned of the wait once it was over). It goes on here.
        val full = background.async { mutex.withLock { historyLocked() } }
        return kotlinx.coroutines.withTimeoutOrNull(HISTORY_WAIT_MS) { full.await() } ?: historyMeanwhile()
    }

    /** What is known without reading the journal: the device's last coverage and how far the catch-up is. */
    private fun historyMeanwhile(): io.yamsergey.dta.protocol.network.HistorySummary {
        val c = context?.coverage
        val device = if (c != null) io.yamsergey.dta.protocol.network.DeviceHistory(
            entries = deviceEntries, bytes = c.deviceBytes ?: 0, keepBytes = c.keepBytes ?: io.yamsergey.dta.protocol.network.HistoryDefaults.KEEP_BYTES,
            warnBytes = c.warnBytes ?: io.yamsergey.dta.protocol.network.HistoryDefaults.WARN_BYTES, trimHeld = c.trimHeld, lastArchivedMs = c.lastArchivedMs,
            overWarning = c.warnBytes != null && (c.deviceBytes ?: 0) > c.warnBytes!!, catchingUp = catchingUpNow.takeIf { it > 0 }, reading = c.deviceReading == true,
            feedLagMs = feedLag(),
        ) else null
        return io.yamsergey.dta.protocol.network.HistorySummary(device, if (device == null) "this computer is reading the app's history" else null, archiveHistory())
    }

    private suspend fun historyLocked(): io.yamsergey.dta.protocol.network.HistorySummary {
        var unknown: String? = null
        val deviceHistory = try {
            sync(connection())
            val c = context?.coverage
            if (c?.keepBytes == null || c.warnBytes == null) {
                unknown = "this app's DTA library is older and does not report its history's size; update it"
                null
            } else {
                val waiting = epoch?.let { store.waitingCount(it.id) } ?: 0
                val (earliest, latest) = epoch?.let { store.timeSpan(it.id) } ?: (null to null)
                io.yamsergey.dta.protocol.network.DeviceHistory(
                    entries = deviceEntries,
                    bytes = c.deviceBytes ?: 0,
                    earliestMs = c.earliestMs ?: earliest,
                    latestMs = latest,
                    archivedEntries = (deviceEntries - waiting).coerceAtLeast(0).takeIf { lag == 0L },
                    keepBytes = c.keepBytes!!,
                    warnBytes = c.warnBytes!!,
                    trimHeld = c.trimHeld,
                    lastArchivedMs = c.lastArchivedMs,
                    overWarning = (c.deviceBytes ?: 0) > c.warnBytes!!,
                    catchingUp = lag.takeIf { it > 0 },
                    reading = c.deviceReading == true,
                    feedLagMs = feedLag(),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            unknown = "the app is not running or cannot be reached (${e.message ?: e.javaClass.simpleName}); start it to see its part"
            null
        }
        return io.yamsergey.dta.protocol.network.HistorySummary(deviceHistory, unknown, archiveHistory(),
            hostOnly = (liveCount() - deviceEntries).toLong().takeIf { deviceHistory != null && lag == 0L && it > 0 })
    }

    /** This app's epochs on this device, live and archived, with their totals. */
    private fun archiveHistory(): io.yamsergey.dta.protocol.network.ArchiveHistory {
        val installations = store.listing().devices.filter { it.key == device.key }
            .flatMap { it.apps }.filter { it.packageName == packageName }.flatMap { it.installations }
        val epochs = installations.flatMap { it.epochs }
        return io.yamsergey.dta.protocol.network.ArchiveHistory(
            entries = epochs.sumOf { it.entries.toLong() },
            bytes = epochs.sumOf { it.bytes },
            earliestMs = epochs.minOfOrNull { it.openedMs },
            latestMs = epochs.maxOfOrNull { it.closedMs ?: clock() },
            installations = installations,
            startedOver = store.startedOver,
        )
    }

    /**
     * Feature 066 (research R6): before the device forgets, everything it has that this computer
     * does not is copied, within [timeoutMs]. What is still missing then is returned, with why.
     */
    suspend fun copyBeforeClear(timeoutMs: Long = CLEAR_COPY_MS): io.yamsergey.dta.protocol.network.NotKept? {
        val deadline = clock() + timeoutMs
        var why = "the copy did not finish in ${timeoutMs / 1000} s"
        try {
            mutex.withLock {
                val app = connection()
                // The whole journal before a clear, whoever else reads it.
                while (sync(app, pages = catchUpBudgetPages) && lag > 0 && clock() < deadline) Unit
            }
            // A body the copy worker (or an answer) is copying is waited for, not skipped (D-005);
            // one this copy tried and could not take stays counted.
            val tried = HashSet<Pair<String, BodyPart>>()
            while (clock() < deadline) {
                val left = pendingBodies().filter { (it.entryId to it.part) !in tried }
                if (left.isEmpty()) break
                var waiting = false
                for (body in left) {
                    if (clock() >= deadline) break
                    val key = body.entryId to body.part
                    if (key in copying) { waiting = true; continue }
                    copyBody(body.entryId, body.part)
                    tried += key
                }
                if (waiting) kotlinx.coroutines.delay(CLEAR_WAIT_MS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            why = "the copy failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return mutex.withLock {
            val bodies = pending().size
            val entries = lag.toInt()
            if (bodies == 0 && entries == 0) null else io.yamsergey.dta.protocol.network.NotKept(entries, bodies, why)
        }
    }

    /** Feature 066: tell the device now what is archived (after the copy worker copied bodies). */
    suspend fun acknowledgeNow() = mutex.withLock { runCatching { connection() }.getOrNull()?.let { acknowledge(it, force = true) } }

    /**
     * Feature 066 (research R1): what this computer has archived, so the device may remove it:
     * the cursor of the last complete catch-up, less the entries whose bodies are not copied yet.
     * None while the copy is far behind or the archive stopped, said in [archiveHeld]; at most one
     * every [ACK_EVERY_MS] unless [force]. A failure never fails the answer.
     */
    private suspend fun acknowledge(app: AppConnection, force: Boolean = false) {
        val now = clock()
        if (!force && lastAckMs?.let { now - it < ACK_EVERY_MS } == true) return
        val cursor = store.cursor(device.key, packageName) ?: return
        val position = cursor.position ?: return
        if (store.archivingStopped != null) { archiveHeld = "archive-stopped"; return }
        val keep = pending().map { it.entryId }.distinct()
        if (keep.size > MAX_KEEP) { archiveHeld = "copy-behind"; return }
        archiveHeld = null
        lastAckMs = now
        try {
            app.request(
                "network_archived",
                DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.ArchiveAck.serializer(),
                    io.yamsergey.dta.protocol.network.ArchiveAck(cursor.journalId, store.hostId, position, keep)).jsonObject,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sent again with the next answer.
        }
    }

    /** Feature 040: the entries with search hits, over the host copy (live or archived). */
    suspend fun search(query: NetworkQuery): SearchPage = search(query) { v -> v.search(query) }

    /** The search through [pass] on a snapshot outside the mutex (feature 067). */
    internal suspend fun search(query: NetworkQuery, pass: (NetworkHistoryView) -> SearchPage): SearchPage {
        query.archiveScope?.let { scope ->
            val epochs = store.epochs(device.key, packageName, scope)
            return archiveView(epochs).search(query).copy(scope = explicitScope(scope, epochs))
        }
        var at: Pair<String?, HistoryContext>? = null
        val toCopy = mutex.withLock {
            val app = connection()
            if (!sync(app)) {
                return SearchPage(emptyList(), degraded = listOf(DegradationMarker(NetworkHistoryView.CAPABILITY, DegradationCodes.NETWORK_SEARCH_UNAVAILABLE)))
            }
            // One hold when nothing is to copy (see [list]).
            pending().also { if (it.isEmpty()) at = epoch?.id to context!! }
        }
        copyAll(toCopy)
        val (epochId, ctx) = at ?: mutex.withLock { epoch?.id to context!! }
        return livePass(query, epochId, ctx, pass).copy(scope = scopeOf(query, epochId, ctx))
    }

    /**
     * Feature 071: a streamed search's walk, ready to run on the search pool: caught up and its bodies
     * copied first (as [search]), then [Pass.walk] reads the scope on one snapshot, outside the mutex.
     */
    class Pass(val scope: io.yamsergey.dta.protocol.network.HistoryScope, val walk: (stop: io.yamsergey.dta.protocol.network.PatternStop, onItem: (io.yamsergey.dta.protocol.network.FoundItem) -> Unit) -> io.yamsergey.dta.protocol.network.FindEnd)

    suspend fun find(query: NetworkQuery, limit: Int?): Pass {
        query.archiveScope?.let { scope ->
            val epochs = store.epochs(device.key, packageName, scope)
            val ids = epochs.map { it.id }
            val marks = epochs.associate { it.id to mark(it) }
            return Pass(explicitScope(scope, epochs)) { stop, onItem ->
                archiveView(epochs, stop).find(query, limit) { item -> onItem(item.copy(archived = store.epochOf(ids, item.entry.id)?.let { marks.getValue(it) })) }
            }
        }
        var at: Pair<String?, HistoryContext>? = null
        val toCopy = mutex.withLock {
            val app = connection()
            check(sync(app)) { "this app keeps no history to search on this computer" }
            (if (needsBodies(query)) pending() else emptyList()).also { if (it.isEmpty()) at = epoch?.id to context!! }
        }
        copyAll(toCopy)
        val (epochId, ctx) = at ?: mutex.withLock { epoch?.id to context!! }
        val scope = scopeOf(query, epochId, ctx)
        return Pass(scope) { stop, onItem ->
            val view = if (query.archive) wholeView(epochId, ctx, stop) else liveView(epochId, ctx, runBound(epochId, ctx), stop)
            val archivedMarks = if (query.archive) store.epochs(device.key, packageName, io.yamsergey.dta.protocol.network.ArchiveScope(all = true)).associate { it.id to mark(it) } else emptyMap()
            val archivedIds = archivedMarks.keys.toList()
            view.find(query, limit) { item ->
                val mark = if (archivedIds.isEmpty() || (epochId != null && store.entry(epochId, item.entry.id) != null)) null
                else store.epochOf(archivedIds, item.entry.id)?.let { archivedMarks.getValue(it) }
                onItem(if (mark != null) item.copy(archived = mark) else item)
            }
        }
    }

    /**
     * Feature 065: the entries of the live history [query] lets through (Network's evaluator), for
     * the Timeline. Throws, saying why, when the app keeps no journal to copy.
     */
    suspend fun passing(query: NetworkQuery): io.yamsergey.dta.protocol.network.NetworkHistoryView.Passing {
        val toCopy = mutex.withLock {
            val app = connection()
            check(sync(app)) { "this app's network history is not copied to this computer, so Network's filters cannot be applied to its timeline" }
            if (needsBodies(query)) pending() else emptyList()
        }
        copyAll(toCopy)
        val (epochId, ctx) = mutex.withLock { epoch?.id to context!! }
        return liveView(epochId, ctx).passing(query)
    }

    private fun needsBodies(query: NetworkQuery): Boolean {
        val (filter, terms) = query.resolved()
        fun bodies(f: NetworkFilter) = f.bodyContains != null || f.requestContains != null || f.responseContains != null || f.hasPattern
        return terms.isNotEmpty() || filter?.let { bodies(it) || it.exclude?.any(::bodies) == true } == true
    }

    /**
     * The app answers itself (no storage): it is given the filters every app version knows; what
     * only the host can do (search words, host:, exclusions) is left out and said so.
     */
    
    /** Catches up with the journal without answering anything (the copy worker's loop). */
    suspend fun refresh() = mutex.withLock { sync(connection()) }

    /** The device cleared [clearedEntries]: those the host never read are counted on the archived epoch. */
    suspend fun cleared(clearedEntries: Int) = mutex.withLock {
        neverReceived = (clearedEntries - liveCount()).coerceAtLeast(0)
        // 011 US1: the cleared history is an archived epoch as soon as the clear answers, not at the next answer (D-006).
        try {
            sync(connection(), pages = 1)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next answer closes it.
        }
    }

    /** An entry of the live history, else of the archive (marked); the archive answers with the app gone. */
    suspend fun entry(id: String): NetworkEntryDetail = mutex.withLock {
        val app = runCatching { connection() }.getOrNull()
        if (app != null) {
            if (!sync(app)) return DtaJson.decodeFromJsonElement(NetworkEntryDetail.serializer(), app.request("network_entry", buildJsonObject { put("id", id) }))
            if (epoch?.let { store.entry(it.id, id) } != null) return liveView(epoch?.id, context ?: emptyContext()).entry(id)
        }
        val (archivedEpoch, _) = store.findArchived(device.key, packageName, id) ?: run {
            if (app == null) connection() // the app is gone and the archive has nothing: say why the app cannot answer
            // D-009: subscribed, a record the app wrote may still be on its way in a batch; the app answers for it.
            if (app != null && feedSub != null) return DtaJson.decodeFromJsonElement(NetworkEntryDetail.serializer(), app.request("network_entry", buildJsonObject { put("id", id) }))
            return liveView(epoch?.id, context ?: emptyContext()).entry(id)
        }
        archiveView(listOf(archivedEpoch)).entry(id).copy(archived = mark(archivedEpoch))
    }

    /** A body as the host holds it (live or archived), or null when it has not been copied. */
    suspend fun storedBody(id: String, part: BodyPart): Pair<BodyInfo, ByteArray>? = mutex.withLock {
        val (epochId, entry) = epoch?.let { e -> store.entry(e.id, id)?.let { e.id to it } }
            ?: store.findArchived(device.key, packageName, id)?.let { (e, entry) -> e.id to entry }
            ?: return null
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        // 070 E13: a body its preview holds whole is in the record, not in a file.
        info.wholeText()?.let { return info to it.toByteArray(Charsets.UTF_8) }
        val sha = store.bodyRef(epochId, id, part)?.sha256 ?: return null
        val bytes = store.openBody(sha)?.use { it.readBytes() } ?: return null
        info to bytes
    }

    /** Archived epochs answer the same queries from the host alone (US4), read from disk (feature 069). */
    private fun archived(query: NetworkQuery, scope: io.yamsergey.dta.protocol.network.ArchiveScope): NetworkPage {
        val epochs = store.epochs(device.key, packageName, scope)
        val page = archiveView(epochs).list(query)
        val ids = epochs.map { it.id }
        val marks = epochs.associate { it.id to mark(it) }
        return page.copy(
            archived = page.entries.mapNotNull { e -> store.epochOf(ids, e.id)?.let { e.id to marks.getValue(it) } }.toMap().ifEmpty { null },
            scope = explicitScope(scope, epochs),
        )
    }

    /** Feature 071: an explicit archived part answered instead of the switch's scope. */
    private fun explicitScope(scope: io.yamsergey.dta.protocol.network.ArchiveScope, epochs: List<Epoch>) =
        io.yamsergey.dta.protocol.network.HistoryScope(archive = false, entries = epochs.sumOf { store.networkCount(it.id).toLong() }, explicit = scope.text())

    /** [epochs] (in the order they were opened) as one answer reads them, from disk (feature 069). */
    private fun archiveView(epochs: List<Epoch>, stop: io.yamsergey.dta.protocol.network.PatternStop = io.yamsergey.dta.protocol.network.PatternStop.NONE): NetworkHistoryView {
        val ids = epochs.map { it.id }
        val stored = ids.sumOf { store.entryCount(it) }
        val context = HistoryContext(
            runId = "",
            coverage = io.yamsergey.dta.protocol.network.Coverage(searched = stored, stored = stored, persisted = true),
            capture = io.yamsergey.dta.protocol.rpc.CapabilityStatus("network", io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status.AVAILABLE),
        )
        fun byId(id: String): NetworkEntry? = store.epochOf(ids, id)?.let { store.entry(it, id) }
        return NetworkHistoryView.over(
            store.sources({ ids }), { context },
            { entry, part ->
                val info = NetworkHistoryView.bodyOf(entry, part)
                // A preview that is all of the body is the body (as for the live epoch: no disk read).
                info?.wholeText()?.let { return@over NetworkFilterEval.BodyBytes(it.toByteArray(Charsets.UTF_8), true) }
                val raw = info?.let { store.epochOf(ids, entry.id)?.let { e -> rawBody(e, entry.id, part) } }
                raw?.let { BodyCodec.decode(it, info.encoding).let { d -> NetworkFilterEval.BodyBytes(d.bytes, d.decoded) } }
            },
            { entry, part -> decodedJson(entry, part, store.epochOf(ids, entry.id), ::byId) },
            readEnvelope, { stop },
        )
    }

    private fun rawBody(epochId: String, entryId: String, part: BodyPart): ByteArray? {
        val sha = store.bodyRef(epochId, entryId, part)?.sha256 ?: return null
        return store.openBody(sha)?.use { it.readBytes() }
    }

    /** A host-held binary body as the app's schemas decode it; null when none applies or it is not here. */
    private fun decodedJson(entry: NetworkEntry, part: BodyPart, epochId: String?, byId: (String) -> NetworkEntry?): String? {
        val d = decoding()
        if (d === io.yamsergey.dta.daemon.network.proto.BodyDecoding.NONE || epochId == null) return null
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        val mappingPart = when (part) {
            BodyPart.REQUEST, BodyPart.APP_REQUEST -> io.yamsergey.dta.daemon.network.proto.MappingPart.REQUEST
            BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> io.yamsergey.dta.daemon.network.proto.MappingPart.RESPONSE
            BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> io.yamsergey.dta.daemon.network.proto.MappingPart.MESSAGE
        }
        val target = when (entry) {
            is HttpEntry -> io.yamsergey.dta.daemon.network.proto.BodyTarget(entry.url, entry.method, mappingPart, null, info.contentType)
            is io.yamsergey.dta.protocol.network.WsMessageEntry -> {
                val socket = byId(entry.connectionId) as? WsConnectionEntry ?: return null
                io.yamsergey.dta.daemon.network.proto.BodyTarget(socket.url, null, mappingPart, entry.direction, info.contentType)
            }
            else -> return null
        }
        val raw = rawBody(epochId, entry.id, part) ?: return null
        return runCatching { d.decode(target, raw, info).json }.getOrNull()
    }

    private fun mark(e: Epoch) = io.yamsergey.dta.protocol.network.ArchivedMark(e.id, e.reason ?: EpochCloseReason.UNKNOWN, e.closedMs ?: 0, store.versionOf(e.id))

    /** Catches up with the journal; false when the app keeps no journal (it answers itself). */
    /**
     * [between]: called under the mutex after each page but the last; it may let others hold the
     * mutex meanwhile (the background catch-up). If the cursor or the epoch moved meanwhile, the
     * round ends there, and the next one reads from where the store is.
     */
    private suspend fun sync(app: AppConnection, pages: Int = queryPages(), between: (suspend () -> Unit)? = null): Boolean {
        val identity = DtaJson.decodeFromJsonElement(JournalIdentityResult.serializer(), app.request("network_identity"))
        archiving = identity.persisted
        if (!identity.persisted) return false
        context = identity.context
        deviceEntries = identity.entries
        var current = currentEpoch(app, identity)
        val saved = store.cursor(device.key, packageName)
        // Feature 068: subscribed to this journal, the feed brings what is written; no page is read.
        // D-009: only a subscription on this very channel; one of an earlier connection (the app restarted) brings nothing.
        if (feedSub != null) {
            if (feedConnection === app && saved?.journalId == identity.journalId) { lag = 0; return true }
            feedSub = null
        }
        var position = saved?.position
        var lastId = saved?.lastId
        // The first page starts on the line of the last record read, so reading it also checks it (feature 046).
        var check = previousLine(position)
        // A page that only finds the journal rolled back does not count: the next one reads from its start.
        var read = 0
        while (read < pages) run page@{
            val params = buildJsonObject {
                (check ?: position)?.let { put("after", it) }
                // One more for the record checked, so that every page moves on.
                put("limit", if (check != null) pageSize + 1 else pageSize)
            }
            val page = DtaJson.decodeFromJsonElement(JournalPageResult.serializer(), app.request("network_journal", params))
            // The journal changed while reading: the next answer starts the new epoch.
            if (page.journalId != identity.journalId) return true
            var records = page.records
            var next = page.next
            check?.let { line ->
                check = null
                when (continuity(app, line, lastId, records.firstOrNull(), identity)) {
                    Continuity.SAME -> records = records.drop(1)
                    Continuity.GOES_ON -> if (records.isEmpty() && next == line) next = position!!
                    Continuity.ROLLED_BACK -> {
                        current = reopen(app, current, identity)
                        position = null
                        lastId = null
                        return@page
                    }
                }
            }
            read++
            if (records.isNotEmpty()) {
                val created = store.putEntries(current.id, records)
                // Feature 068: small bodies come with their records; nothing is asked for them.
                storeBodies(current.id, page.bodies)
                putLive(current.id, records, created)
                lastId = records.last().id
            }
            position = next
            store.setCursor(device.key, packageName, identity.journalId, position, lastId)
            catchingUpNow = if (page.end) 0 else page.remaining.toLong().coerceAtLeast(1)
            behindNow = catchingUpNow
            if (page.end) {
                lag = 0
                acknowledge(app)
                return true
            }
            if (between != null && read < pages) {
                between()
                if (epoch?.id != current.id || store.cursor(device.key, packageName)?.position != position) {
                    lag = behindNow
                    return true
                }
            }
        }
        lag = behindNow
        return true
    }

    private enum class Continuity { SAME, GOES_ON, ROLLED_BACK }

    /** Feature 068: what [apply] did with a batch. */
    enum class Applied { APPLIED, DUPLICATE, GAP, STALE }

    /**
     * Feature 068 (research R7): every entry the archive takes, for watchers (the plugin, agents,
     * `--follow`). Never waits: a watcher that falls behind is told how many it missed with the next one.
     */
    private val appliedFlow = kotlinx.coroutines.flow.MutableSharedFlow<io.yamsergey.dta.protocol.network.NetworkEntryEvent>(extraBufferCapacity = APPLIED_BUFFER)
    val applied: kotlinx.coroutines.flow.SharedFlow<io.yamsergey.dta.protocol.network.NetworkEntryEvent> = appliedFlow
    private var missed = 0

    /** Feature 068: this app's history is in a journal this computer archives (else the app's own events are its live entries). */
    @Volatile
    var archiving: Boolean = false
        private set

    private fun emitApplied(entry: NetworkEntry, created: Boolean) {
        if (appliedFlow.subscriptionCount.value == 0) return
        val change = if (created) io.yamsergey.dta.protocol.network.EntryChange.CREATED else io.yamsergey.dta.protocol.network.EntryChange.UPDATED
        if (appliedFlow.tryEmit(io.yamsergey.dta.protocol.network.NetworkEntryEvent(entry.summary(), change, missed.takeIf { it > 0 }))) missed = 0 else missed++
    }

    /** Feature 068: the subscription this computer holds on the app's journal, if any. */
    @Volatile
    private var feedSub: String? = null

    /** The channel [feedSub] was opened on. */
    @Volatile
    private var feedConnection: AppConnection? = null

    val feedLive: Boolean get() = feedSub != null

    /**
     * Feature 068 (research R1): reads the journal to its end by pages, then subscribes from there;
     * from then on the app sends each record as it writes it ([apply]). Under the mutex, so a batch
     * that arrives before the answer waits for it. Null when there is no journal or it is not read
     * to its end yet.
     */
    suspend fun openFeed(): io.yamsergey.dta.protocol.network.FeedOpenResult? {
        readsInBackground = true
        try {
            mutex.lock()
            // Only once it holds the mutex: an answer that took it first (dialling the app) reads its own page,
            // else it would read none and say it is caught up (fix-g: D-004/D-010/D-015).
            catchingUpInBackground = true
            try {
                val app = connection()
                feedSub = null
                // One round reads up to the budget of pages, but lets go of the mutex between them:
                // a query beside the catch-up waits for a page, not for the round.
                val read = sync(app, pages = catchUpBudgetPages, between = {
                    mutex.unlock()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutex.lock() }
                })
                if (!read || lag > 0) return null
                return subscribe(app)
            } finally {
                mutex.unlock()
            }
        } finally {
            catchingUpInBackground = false
        }
    }

    /** Subscribes from the position this computer holds; under the mutex, read to the journal's end. */
    private suspend fun subscribe(app: AppConnection): io.yamsergey.dta.protocol.network.FeedOpenResult? {
        val saved = store.cursor(device.key, packageName) ?: return null
        val params = DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.FeedOpenParams.serializer(),
            io.yamsergey.dta.protocol.network.FeedOpenParams(saved.journalId, saved.position ?: "0:0")).jsonObject
        val result = DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.FeedOpenResult.serializer(), app.request("network_feed_open", params))
        feedSub = result.subscription
        feedConnection = app
        return result
    }

    /**
     * Feature 068 (research R6): a batch of the subscription goes into the archive when it starts at
     * the position this computer holds; one it already has is skipped; a gap ends the subscription
     * (pages are read again); one of an earlier subscription is not taken.
     */
    suspend fun apply(batch: io.yamsergey.dta.protocol.network.FeedBatch, receivedMs: Long = clock()): Applied = mutex.withLock {
        if (batch.subscription != feedSub) return Applied.STALE
        val saved = store.cursor(device.key, packageName)
        val current = epoch
        if (saved == null || current == null || saved.journalId != batch.journalId) { feedSub = null; return Applied.GAP }
        val position = saved.position ?: "0:0"
        if (batch.from != position) {
            if (cursorAtOrBefore(batch.to, position)) return Applied.DUPLICATE
            feedSub = null
            return Applied.GAP
        }
        var lastId = saved.lastId
        if (batch.records.isNotEmpty()) {
            val created = store.putEntries(current.id, batch.records)
            storeBodies(current.id, batch.bodies)
            putLive(current.id, batch.records, created)
            lastId = batch.records.last().id
        }
        store.setCursor(device.key, packageName, batch.journalId, batch.to, lastId)
        lag = 0
        batch.waitedMs?.let { waited -> clock().let { now -> feedLags.addLast(now to waited + (now - receivedMs).coerceAtLeast(0)) } }
        runCatching { acknowledge(connection()) }
        Applied.APPLIED
    }

    /** Feature 068: the app ended the subscription; pages are read again before the next one. */
    fun feedEnded(ended: io.yamsergey.dta.protocol.network.FeedEnded) {
        if (ended.subscription == feedSub) feedSub = null
    }

    /**
     * Feature 068: an event of the app's channel. A feed batch goes in ([apply]); the subscription
     * ends when the app ends it or says it dropped events (a batch may be among them).
     */
    suspend fun onAppEvent(event: io.yamsergey.dta.protocol.rpc.Event) {
        // Before waiting for the archive: that wait is part of how far behind it is.
        val received = clock()
        when (event.name) {
            io.yamsergey.dta.protocol.network.FeedBatch.EVENT -> event.data?.let {
                runCatching { DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.FeedBatch.serializer(), it) }.getOrNull()
            }?.let { apply(it, received) }
            io.yamsergey.dta.protocol.network.FeedEnded.EVENT -> event.data?.let {
                runCatching { DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.FeedEnded.serializer(), it) }.getOrNull()
            }?.let { feedEnded(it) }
            EVENTS_DROPPED -> feedSub = null
        }
    }

    /** Feature 068: the app's channel is another one (reconnected): its subscription is gone. */
    fun connectionChanged() {
        feedSub = null
    }

    private fun storeBodies(epochId: String, bodies: List<io.yamsergey.dta.protocol.network.FeedBody>?) {
        bodies?.forEach { b -> b.bytes().inputStream().use { store.putBody(epochId, b.entryId, b.part, it) } }
    }

    /**
     * Is this still the journal the daemon was reading (feature 046)? An emulator's snapshot takes
     * the journal back with its id, so the id alone cannot say. [first] is what the journal has on
     * the [line] of the last record read, [lastId].
     */
    private suspend fun continuity(app: AppConnection, line: String, lastId: String?, first: NetworkEntry?, identity: JournalIdentityResult): Continuity {
        val coverage = identity.context.coverage
        // Feature 066: entries the device removed after archiving are gone the same way as evicted ones.
        val removed = coverage.evicted + (coverage.archivedOnly?.values?.sum() ?: 0)
        if (lastId == null) {
            // No record was read at this position: only one beyond the journal shows.
            return if (first == null && removed == 0) Continuity.ROLLED_BACK else Continuity.GOES_ON
        }
        // D-031: the last record read, not only its entry: a pending and a finished record of one entry share its id,
        // so the entry's earlier record on that line (the journal went back past its completion) is not it.
        val last = epoch?.let { store.entry(it.id, lastId) }
        fun isLast(record: NetworkEntry?) = record != null && record.id == lastId && (last == null || record == last)
        if (isLast(first)) return Continuity.SAME
        // Feature 066: the device removed what this computer archived up to there; a missing line is that, not a rollback.
        coverage.archivedThrough?.let { through -> if (cursorAtOrBefore(line, through)) return Continuity.GOES_ON }
        val time = last?.timeMs
        val evicted = removed > 0 && (coverage.earliestMs == null || time == null || time <= coverage.earliestMs!!)
        if (evicted) return Continuity.GOES_ON
        // That line was cut when read (being written, or a crash): the record is one line earlier.
        val earlier = previousLine(line) ?: return Continuity.ROLLED_BACK
        val params = buildJsonObject {
            put("after", earlier)
            put("limit", 1)
        }
        val before = DtaJson.decodeFromJsonElement(JournalPageResult.serializer(), app.request("network_journal", params))
        return if (isLast(before.records.firstOrNull())) Continuity.GOES_ON else Continuity.ROLLED_BACK
    }

    /** The journal went back: what was read is archived, and it is read again from its start. */
    private suspend fun reopen(app: AppConnection, current: Epoch, identity: JournalIdentityResult): Epoch {
        store.closeEpoch(current.id, clock(), EpochCloseReason.ROLLED_BACK, neverReceived)
        neverReceived = 0
        val opened = store.openEpoch(device, packageName, installation(app), identity.journalId, clock())
        resetLive()
        store.setCursor(device.key, packageName, identity.journalId, null)
        epoch = opened
        return opened
    }

    /** True when `segment:line` [position] is at or before [other]. */
    private fun cursorAtOrBefore(position: String, other: String): Boolean {
        fun parts(p: String) = p.split(':').takeIf { it.size == 2 }?.let { (s, l) -> (s.toIntOrNull() ?: return null) to (l.toIntOrNull() ?: return null) }
        val (a, b) = parts(position) ?: return false
        val (c, d) = parts(other) ?: return false
        return a < c || (a == c && b <= d)
    }

    /** The position one line earlier, for a `segment:line` position; null when there is none. */
    private fun previousLine(position: String?): String? {
        val parts = position?.split(':')?.takeIf { it.size == 2 } ?: return null
        val line = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return "${parts[0]}:${line - 1}"
    }

    /** The open epoch of this journal; a different journal closes the old one (the archive) and opens one. */
    private suspend fun currentEpoch(app: AppConnection, identity: JournalIdentityResult): Epoch {
        // Feature 069: nothing of the epoch is read here; answers read it from disk.
        val known = epoch ?: store.liveEpoch(device.key, packageName)?.also { resetLive() }
        val cursor = store.cursor(device.key, packageName)
        if (known != null && cursor?.journalId == identity.journalId && known.journalId == identity.journalId) return known.also { epoch = it }
        val installation = installation(app)
        known?.let {
            store.closeEpoch(it.id, clock(), closeReason(identity, store.installationOf(it.id), installation), neverReceived)
            neverReceived = 0
        }
        val opened = store.openEpoch(device, packageName, installation, identity.journalId, clock())
        resetLive()
        store.setCursor(device.key, packageName, identity.journalId, null)
        epoch = opened
        return opened
    }

    /**
     * Why the device forgot (research R7): a clear says so; new storage is a new build when the
     * version changed, a reinstall when only the install time did, else the app's data was wiped.
     */
    private fun closeReason(identity: JournalIdentityResult, before: InstallationInfo?, now: InstallationInfo): EpochCloseReason = when {
        identity.reason == "cleared" -> EpochCloseReason.CLEARED
        identity.reason != "storage-new" || before == null -> EpochCloseReason.UNKNOWN
        before.versionCode != now.versionCode || before.versionName != now.versionName || before.signingSha256 != now.signingSha256 -> EpochCloseReason.NEW_BUILD
        before.lastUpdateMs != now.lastUpdateMs -> EpochCloseReason.REINSTALLED
        else -> EpochCloseReason.DATA_WIPED
    }

    private suspend fun installation(app: AppConnection): InstallationInfo {
        val report = runCatching { DtaJson.decodeFromJsonElement(IdentityReport.serializer(), app.request("identity")) }.getOrNull()
        val a = report?.app ?: return InstallationInfo()
        return InstallationInfo(a.versionName, a.versionCode, a.lastUpdateMs, a.signingSha256)
    }

    /** A body of a finished entry not on the host yet. */
    data class PendingBody(val entryId: String, val part: BodyPart, val bytes: Long)

    /** Bodies of finished entries in the live epoch that the host has not copied (US7). */
    suspend fun pendingBodies(): List<PendingBody> = mutex.withLock { pending() }

    private fun pending(): List<PendingBody> = epoch?.let { e -> store.pendingBodies(e.id).map { (id, part, bytes) -> PendingBody(id, part, bytes) } }.orEmpty()

    /** Entries of the live epoch on this computer. */
    private fun liveCount(): Int = epoch?.let { store.entryCount(it.id) } ?: 0

    private fun inFlight(entry: NetworkEntry): Boolean = when (entry) {
        is HttpEntry -> entry.state == HttpState.PENDING
        is WsConnectionEntry -> entry.state == WsState.OPEN || entry.state == WsState.CONNECTING
        else -> false
    }

    /**
     * Bodies a body search needs, copied to the host first, newest first (the first page), for at
     * most [searchCopyMs]; one the copy worker is copying is left to it. The copy worker copies
     * every body in time.
     */
    private suspend fun copyAll(bodies: List<PendingBody>) {
        val deadline = clock() + searchCopyMs
        for (body in bodies.asReversed()) {
            if (clock() >= deadline) break
            copyBody(body.entryId, body.part)
        }
    }

    /**
     * Copies one body of the live epoch in [CHUNK] ranges over the app's channel, resuming a
     * partial copy; [paced] runs after each chunk with its bytes and round-trip (the worker's
     * budget). A lost link propagates (the copy resumes later); a body the app no longer has is
     * marked "not archived" with the reason.
     */
    suspend fun copyBody(entryId: String, part: BodyPart, paced: suspend (bytes: Long, ms: Long) -> Unit = { _, _ -> }) {
        val e = epoch ?: return
        val key = entryId to part
        if (!store.isPending(e.id, entryId, part) || !copying.add(key)) return
        try {
            copyOne(e, entryId, part, paced)
        } finally {
            copying.remove(key)
        }
    }

    private suspend fun copyOne(e: Epoch, entryId: String, part: BodyPart, paced: suspend (bytes: Long, ms: Long) -> Unit) {
        // A body all in its preview (a small text message): its record is its copy (070 E13); nothing to fetch.
        if (store.entry(e.id, entryId)?.let { NetworkHistoryView.bodyOf(it, part)?.wholeText() } != null) {
            store.dropPending(e.id, entryId, part)
            copied.tryEmit(entryId)
            return
        }
        val app = connection()
        val partial = store.partialFile(e.id, entryId, part)
        val partName = DtaJson.encodeToJsonElement(BodyPart.serializer(), part).jsonPrimitive.content
        try {
            while (true) {
                val offset = partial.length()
                val started = clock()
                val response = app.requestFull(
                    "network_body_range",
                    buildJsonObject {
                        put("id", entryId)
                        put("part", partName)
                        put("offset", offset)
                        put("length", CHUNK)
                    },
                )
                val range = DtaJson.decodeFromJsonElement(BodyRangeResult.serializer(), response.result)
                java.io.FileOutputStream(partial, true).use { out -> response.binaryPayloads.forEach(out::write) }
                paced(range.bytes, clock() - started)
                if (range.bytes <= 0 || partial.length() >= range.totalBytes) break
            }
            partial.inputStream().use { store.putBody(e.id, entryId, part, it) }
            partial.delete()
            copied.tryEmit(entryId)
        } catch (lost: ConnectionLostException) {
            throw lost
        } catch (timeout: RequestTimeoutException) {
            throw timeout
        } catch (_: ArchivingStoppedException) {
            // Stated in coverage (archivingStopped).
        } catch (gone: SidekickRpcException) {
            // Only the app saying it has no such body makes it lost: the archive acknowledgement then lets the device drop it.
            partial.delete()
            store.markNotArchived(e.id, entryId, part, "the app no longer had it: ${gone.message ?: "no body"}")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The answer gave way or the daemon stops: the partial copy resumes later.
            throw cancelled
        } catch (_: Exception) {
            // A failure on this computer (its files): the body stays pending, copied again later.
            partial.delete()
        }
    }

    /** The body is all in its preview (a small message, a short answer): searched as is, no copy needed for it. */
    private fun whole(entry: NetworkEntry?, part: BodyPart): Boolean =
        entry != null && NetworkHistoryView.bodyOf(entry, part)?.wholeText() != null

    private fun hostBodyBytes(epochId: String?, entry: NetworkEntry, part: BodyPart): NetworkFilterEval.BodyBytes? {
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        // Feature 067: a preview that is all of the body is the body (and costs no store lookup).
        info.wholeText()?.let { return NetworkFilterEval.BodyBytes(it.toByteArray(Charsets.UTF_8), true) }
        epochId ?: return null
        val sha = store.bodyRef(epochId, entry.id, part)?.sha256 ?: return null
        val raw = store.openBody(sha)?.use { it.readBytes() } ?: return null
        val decoded = BodyCodec.decode(raw, info.encoding)
        return NetworkFilterEval.BodyBytes(decoded.bytes, decoded.decoded)
    }

    companion object {
        private const val CHUNK = 256L * 1024
        /** Feature 066: acknowledgements at most this often per app, and at most this many entries kept for their bodies. */
        const val ACK_EVERY_MS = 10_000L
        /** Feature 068: entries held for a watcher that has not taken them yet. */
        const val APPLIED_BUFFER = 65_536
        /** The app's word that a session's events were dropped (its SessionHub). */
        const val EVENTS_DROPPED = "network.events-dropped"
        /** How far back [DeviceHistory.feedLagMs] looks. */
        const val LAG_WINDOW_MS = 10_000L
        /** How long [history] waits for its full answer before it answers with what is known. */
        const val HISTORY_WAIT_MS = 300L
        /** How long a clear waits for the copy of what this computer does not have yet. */
        const val CLEAR_COPY_MS = 20_000L
        /** How often a clear looks again at a body another copy is taking. */
        private const val CLEAR_WAIT_MS = 20L
        /** Feature 067: an answer's own body copying; measured, a backlog of 100k bodies took 871 s. */
        const val SEARCH_COPY_MS = 2_000L
        const val MAX_KEEP = 2_000

        /**
         * Feature 040: the app answers a list itself — given the filters every app version knows;
         * search words, host: and exclusions are left out and the page says so.
         */
        suspend fun appAnswers(app: AppConnection, query: NetworkQuery): NetworkPage {
            val (filter, terms) = query.resolved()
            // Feature 053: body patterns stay here too: an app could read them as nothing at all.
            val unsupported = terms.isNotEmpty() || filter?.host != null || filter?.exclude != null || filter?.hasPattern == true || filter?.source != null
            val sent = query.copy(q = null, filter = filter?.withoutPatterns()?.copy(host = null, exclude = null, source = null)?.takeIf { it != NetworkFilter() })
            val page = DtaJson.decodeFromJsonElement(NetworkPage.serializer(), app.request("network_list", sent.toJson()))
            if (!unsupported) return page
            return page.copy(degraded = page.degraded.orEmpty() + DegradationMarker(NetworkHistoryView.CAPABILITY, DegradationCodes.NETWORK_SEARCH_UNAVAILABLE))
        }
    }
}
