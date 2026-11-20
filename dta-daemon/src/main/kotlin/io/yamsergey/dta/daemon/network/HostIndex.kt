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
    private val connection: suspend () -> AppConnection,
) {
    /** Journal records per request, and requests per answer before the rest is left to the next one. */
    var pageSize = 500
    var catchUpBudgetPages = 40

    /** Feature 068: records still to read while a catch-up runs (read without the mutex by [history]). */
    @Volatile
    private var catchingUpNow = 0L

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
    private val live = LinkedHashMap<String, NetworkEntry>()
    @Volatile private var context: HistoryContext? = null
    private var lag = 0L
    /** Entries the device cleared that the host never read, known when the clear went through the daemon. */
    private var neverReceived = 0
    /** Feature 066: entries the device holds (its identity), when the last acknowledgement went, why none goes. */
    private var deviceEntries = 0
    private var lastAckMs: Long? = null
    private var archiveHeld: String? = null

    private val view = NetworkHistoryView({ live.values.toList() }, { live[it] }, { context!! }, { e, p -> hostBodyBytes(epoch?.id, e, p) },
        { entry, part -> decodedJson(entry, part, epoch?.id, live::get) }, readEnvelope)

    /**
     * Feature 067: bodies of finished live entries not copied yet, kept as records arrive and copies
     * finish (it was one store lookup per entry and part on every answer). Copies run outside the mutex.
     */
    /** Feature 067: an entry whose body was just copied here — a live view decides it again (it was decided by its preview). */
    val copied = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1024, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val pendingCopies = java.util.concurrent.ConcurrentHashMap<Pair<String, BodyPart>, PendingBody>()
    /** Bodies being copied now: an answer and the copy worker never append to one partial file at once. */
    private val copying = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, BodyPart>>()

    /** Feature 067: each live entry's change number (new or updated record), and the last one given. */
    private val changeSeq = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val seqs = java.util.concurrent.atomic.AtomicLong()
    private fun nextSeq() = seqs.incrementAndGet()
    private val lastSeq: Long get() = seqs.get()
    /** Feature 067: bumped whenever live entries are dropped (a new epoch, a rollback). */
    var removals = 0L
        private set

    /** Feature 067: socket connections of the live history, read by live decisions outside the mutex. */
    private val liveSockets = java.util.concurrent.ConcurrentHashMap<String, WsConnectionEntry>()

    /** Feature 067: each live view's match sets (a plugin tab, a CLI watch). */
    internal val views = LiveViews()

    /** How the last view pass was made (tests and the measurement). */
    @Volatile internal var lastPass: LiveViews.Kind? = null
        private set

    /** Feature 067: a newer query of a view abandons its running pass. */
    private val viewPasses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val passIds = java.util.concurrent.atomic.AtomicLong()

    private fun stopperFor(view: String): () -> Unit {
        val mine = passIds.incrementAndGet()
        viewPasses[view] = mine
        return { if (viewPasses[view] != mine) throw SupersededException(view) }
    }

    /** A view's pass replaced by a newer query of the same view; the answer is not needed any more. */
    class SupersededException(view: String) : kotlinx.coroutines.CancellationException("superseded by a newer query of view $view")

    /**
     * Feature 067: the list for a live [view]: only what can match is read (the same query again, a
     * recent one, or a narrower one; see [LiveViews]), and what matched is kept for its next query.
     */
    suspend fun list(query: NetworkQuery, view: String): NetworkPage {
        val stop = stopperFor(view)
        return list(query) { v, state ->
            val (kind, candidates) = if (reusable(query)) views.candidates(view, query, state) else LiveViews.Kind.FULL to null
            val ids = HashSet<String>()
            val page = v.list(query, candidates = candidates, stop = stop, onMatch = { e, _ -> ids += e.id })
            if (reusable(query)) views.record(view, query, state, ids) else views.drop(view)
            lastPass = kind
            page
        }
    }

    /** Feature 067: the search for a live [view], over what its list matched (same query) when it can. */
    suspend fun search(query: NetworkQuery, view: String): SearchPage {
        val stop = stopperFor("$view#search")
        return search(query) { v, state ->
            val (kind, candidates) = if (reusable(query) && !readsBodies(query)) views.candidates(view, query, state) else LiveViews.Kind.FULL to null
            lastPass = kind
            v.search(query, candidates = candidates, stop = stop)
        }
    }

    /** Reused match sets give the full pass's answer: not with redaction or body patterns (research R3). */
    private fun reusable(query: NetworkQuery): Boolean = query.archive == null && !query.redact &&
        runCatching { query.resolved().first?.hasPattern != true }.getOrDefault(false)

    /** A filter that reads bodies: a search still runs it on every entry (to count what it searched). */
    private fun readsBodies(query: NetworkQuery): Boolean {
        val f = runCatching { query.resolved().first }.getOrNull() ?: return false
        fun bodies(x: NetworkFilter) = x.bodyContains != null || x.requestContains != null || x.responseContains != null || x.hasPattern
        return bodies(f) || f.exclude?.any(::bodies) == true
    }

    /**
     * Feature 067: [query]'s decision for one live entry (a subscription's event), as a pass would make it
     * now — its bodies from this computer's copy, else their previews — kept in [view]'s current match
     * set. Second: whether the entry was in that set before. Runs outside the mutex.
     */
    fun decide(view: String, query: NetworkQuery, entry: NetworkEntry, socketUrl: (String) -> String?): Pair<io.yamsergey.dta.protocol.network.EntryDecision, Boolean> {
        (entry as? WsConnectionEntry)?.let { liveSockets.putIfAbsent(it.id, it) }
        val epochId = epoch?.id
        val ctx = context ?: HistoryContext("", io.yamsergey.dta.protocol.network.Coverage(searched = 0, stored = 0, persisted = true),
            io.yamsergey.dta.protocol.rpc.CapabilityStatus(NetworkHistoryView.CAPABILITY, io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status.AVAILABLE))
        fun socket(id: String): NetworkEntry? = liveSockets[id]
            ?: socketUrl(id)?.let { WsConnectionEntry(id, entry.runId, 0, entry.library, url = it, state = WsState.OPEN) }
        val v = NetworkHistoryView({ emptyList() }, ::socket, { ctx }, { e, p -> hostBodyBytes(epochId, e, p) },
            { e, p -> decodedJson(e, p, epochId, ::socket) }, readEnvelope)
        val d = v.decide(query, entry)
        return d to views.decided(view, entry.id, d.match)
    }

    /** Feature 067: a live entry as this computer holds it now (after its body was copied). */
    suspend fun liveEntry(id: String): NetworkEntry? = mutex.withLock { live[id] }

    private fun resetLive() {
        liveSockets.clear()
        live.clear()
        changeSeq.clear()
        pendingCopies.clear()
        removals++
    }

    private fun putLive(epochId: String, records: List<NetworkEntry>, emit: Boolean = true) {
        for (r in records) {
            val created = !live.containsKey(r.id)
            live[r.id] = r
            if (emit) emitApplied(r, created)
            if (r is WsConnectionEntry) liveSockets[r.id] = r
            changeSeq[r.id] = nextSeq()
            BodyPart.entries.forEach { pendingCopies.remove(r.id to it) }
            if (inFlight(r)) continue
            for (part in BodyPart.entries) {
                val info = NetworkHistoryView.bodyOf(r, part) ?: continue
                if (info.storedBytes <= 0 || store.bodyRef(epochId, r.id, part) != null) continue
                // Feature 068: a body its preview holds whole (a small text message) is archived with its record.
                val whole = info.wholeText()
                if (whole != null) whole.toByteArray(Charsets.UTF_8).inputStream().use { store.putBody(epochId, r.id, part, it) }
                else pendingCopies[r.id to part] = PendingBody(r.id, part, info.storedBytes)
            }
        }
    }

    /**
     * Feature 067: what a pass over the live history needs, taken under the mutex and read without it,
     * so that opening an entry never waits for a search.
     */
    private class Snapshot(
        val entries: List<NetworkEntry>, val byId: Map<String, NetworkEntry>, val epochId: String?, val context: HistoryContext,
        val seq: Long, val removals: Long, val changes: Map<String, Long>, val pending: Set<String>,
    )

    private fun snapshot() = Snapshot(ArrayList(live.values), HashMap(live), epoch?.id, context!!, lastSeq, removals, HashMap(changeSeq),
        pendingCopies.keys.filter { (id, part) -> !whole(live[id], part) }.mapTo(HashSet()) { it.first })

    private fun Snapshot.state() = LiveState(epochId, seq, removals, entries, changes, pending)

    private fun viewOf(s: Snapshot) = NetworkHistoryView({ s.entries }, { s.byId[it] }, { s.context }, { e, p -> hostBodyBytes(s.epochId, e, p) },
        { entry, part -> decodedJson(entry, part, s.epochId, s.byId::get) }, readEnvelope)

    suspend fun list(query: NetworkQuery): NetworkPage = list(query, pass = { v, _ -> v.list(query) })

    /**
     * The live list through [pass] (feature 067: the whole history, or a view's candidates), run on a
     * snapshot outside the mutex; the coverage this computer adds is read under it, before.
     */
    internal suspend fun list(query: NetworkQuery, pass: (NetworkHistoryView, LiveState) -> NetworkPage): NetworkPage {
        query.archive?.let { return archived(query, it) }
        val toCopy = mutex.withLock {
            val app = connection()
            if (!sync(app)) return appAnswers(app, query)
            if (needsBodies(query)) pending().filterNot { whole(live[it.entryId], it.part) } else emptyList()
        }
        // Copied outside the mutex: opening an entry does not wait for the bodies a search needs.
        copyAll(toCopy)
        val (snap, extra) = mutex.withLock {
            snapshot() to Extra(
                hostLagRecords = lag.takeIf { it > 0 },
                bodiesPending = pendingCopies.size.takeIf { it > 0 },
                bodiesNotArchived = epoch?.let { store.countNotArchived(it.id) }?.takeIf { it > 0 },
                archivedEpochs = store.archivedCount(device.key, packageName).takeIf { it > 0 },
                archivingStopped = store.archivingStopped,
                hostOnly = (live.size - deviceEntries).toLong().takeIf { lag == 0L && it > 0 },
                archiveHeld = archiveHeld,
            )
        }
        val page = pass(viewOf(snap), snap.state())
        return page.copy(
            coverage = page.coverage.copy(
                hostLagRecords = extra.hostLagRecords,
                bodiesPending = extra.bodiesPending,
                bodiesNotArchived = extra.bodiesNotArchived,
                archivedEpochs = extra.archivedEpochs,
                archivingStopped = extra.archivingStopped,
                hostOnly = extra.hostOnly,
                archivedByOther = page.coverage.archivedOnly?.filterKeys { it != store.hostId }?.values?.sum()?.takeIf { it > 0 },
                archiveHeld = extra.archiveHeld,
            ),
        )
    }

    private class Extra(
        val hostLagRecords: Long?, val bodiesPending: Int?, val bodiesNotArchived: Int?, val archivedEpochs: Int?,
        val archivingStopped: String?, val hostOnly: Long?, val archiveHeld: String?,
    )

    /**
     * Feature 067: the live history as one pass saw it — its entries in order, each one's change
     * number, what changed after a number, and which entries a full pass may count as read only in
     * part (a body not copied yet, binary, cut), which a narrowed pass must read again.
     */
    internal class LiveState(
        val epochId: String?,
        val seq: Long,
        val removals: Long,
        val entries: List<NetworkEntry>,
        private val changes: Map<String, Long>,
        private val pending: Set<String>,
    ) {
        fun changedSince(seq: Long): (NetworkEntry) -> Boolean = { (changes[it.id] ?: Long.MAX_VALUE) > seq }

        /** Read in part by a pass: some body is not here whole as text, so the counts it adds depend on the query. */
        fun partial(entry: NetworkEntry): Boolean = entry.id in pending || NetworkHistoryView.bodiesOf(entry).any { b ->
            b.binary || b.completeness != io.yamsergey.dta.protocol.network.Completeness.COMPLETE || (b.storedBytes <= 0 && b.preview != null)
        }
    }

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
                val waiting = (pending().map { it.entryId } + live.values.filter(::inFlight).map { it.id }).toSet().size
                io.yamsergey.dta.protocol.network.DeviceHistory(
                    entries = deviceEntries,
                    bytes = c.deviceBytes ?: 0,
                    earliestMs = c.earliestMs ?: live.values.minOfOrNull { it.timeMs },
                    latestMs = live.values.maxOfOrNull { it.timeMs },
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
            hostOnly = (live.size - deviceEntries).toLong().takeIf { deviceHistory != null && lag == 0L && it > 0 })
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
                while (sync(app) && lag > 0 && clock() < deadline) Unit
            }
            for (body in pendingBodies()) {
                if (clock() >= deadline) break
                copyBody(body.entryId, body.part)
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
    suspend fun search(query: NetworkQuery): SearchPage = search(query) { v, _ -> v.search(query) }

    /** The search through [pass] on a snapshot outside the mutex (feature 067). */
    internal suspend fun search(query: NetworkQuery, pass: (NetworkHistoryView, LiveState) -> SearchPage): SearchPage {
        query.archive?.let { scope -> return mutex.withLock { archiveView(store.epochs(device.key, packageName, scope)) }.search(query) }
        val toCopy = mutex.withLock {
            val app = connection()
            if (!sync(app)) {
                return SearchPage(emptyList(), 0, searched = 0, degraded = listOf(DegradationMarker(NetworkHistoryView.CAPABILITY, DegradationCodes.NETWORK_SEARCH_UNAVAILABLE)))
            }
            pending().filterNot { whole(live[it.entryId], it.part) }
        }
        copyAll(toCopy)
        val snap = mutex.withLock { snapshot() }
        return pass(viewOf(snap), snap.state())
    }

    /**
     * Feature 065: the entries of the live history [query] lets through (Network's evaluator), for
     * the Timeline. Throws, saying why, when the app keeps no journal to copy.
     */
    suspend fun passing(query: NetworkQuery): io.yamsergey.dta.protocol.network.NetworkHistoryView.Passing {
        val toCopy = mutex.withLock {
            val app = connection()
            check(sync(app)) { "this app's network history is not copied to this computer, so Network's filters cannot be applied to its timeline" }
            if (needsBodies(query)) pending().filterNot { whole(live[it.entryId], it.part) } else emptyList()
        }
        copyAll(toCopy)
        val snap = mutex.withLock { snapshot() }
        return viewOf(snap).passing(query)
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
        neverReceived = (clearedEntries - live.size).coerceAtLeast(0)
    }

    /** An entry of the live history, else of the archive (marked); the archive answers with the app gone. */
    suspend fun entry(id: String): NetworkEntryDetail = mutex.withLock {
        val app = runCatching { connection() }.getOrNull()
        if (app != null) {
            if (!sync(app)) return DtaJson.decodeFromJsonElement(NetworkEntryDetail.serializer(), app.request("network_entry", buildJsonObject { put("id", id) }))
            if (live.containsKey(id)) return view.entry(id)
        }
        val (archivedEpoch, _) = store.findArchived(device.key, packageName, id) ?: run {
            if (app == null) connection() // the app is gone and the archive has nothing: say why the app cannot answer
            return view.entry(id)
        }
        archiveView(listOf(archivedEpoch)).entry(id).copy(archived = mark(archivedEpoch))
    }

    /** A body as the host holds it (live or archived), or null when it has not been copied. */
    suspend fun storedBody(id: String, part: BodyPart): Pair<BodyInfo, ByteArray>? = mutex.withLock {
        val (epochId, entry) = epoch?.takeIf { live.containsKey(id) }?.let { it.id to live.getValue(id) }
            ?: store.findArchived(device.key, packageName, id)?.let { (e, entry) -> e.id to entry }
            ?: return null
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        val sha = store.bodyRef(epochId, id, part)?.sha256 ?: return null
        val bytes = store.openBody(sha)?.use { it.readBytes() } ?: return null
        info to bytes
    }

    /** Archived epochs answer the same queries from the host alone (US4). */
    private fun archived(query: NetworkQuery, scope: io.yamsergey.dta.protocol.network.ArchiveScope): NetworkPage {
        val epochs = store.epochs(device.key, packageName, scope)
        val page = archiveView(epochs).list(query)
        val owner = HashMap<String, Epoch>()
        epochs.forEach { e -> store.entries(e.id).forEach { owner[it.id] = e } }
        val marks = epochs.associate { it.id to mark(it) }
        return page.copy(archived = page.entries.associate { it.id to marks.getValue(owner.getValue(it.id).id) }.ifEmpty { null })
    }

    private fun archiveView(epochs: List<Epoch>): NetworkHistoryView {
        val entries = LinkedHashMap<String, NetworkEntry>()
        val epochOf = HashMap<String, String>()
        epochs.forEach { e -> store.entries(e.id).forEach { entries[it.id] = it; epochOf[it.id] = e.id } }
        val context = HistoryContext(
            runId = "",
            coverage = io.yamsergey.dta.protocol.network.Coverage(searched = entries.size, stored = entries.size, persisted = true),
            capture = io.yamsergey.dta.protocol.rpc.CapabilityStatus("network", io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status.AVAILABLE),
        )
        return NetworkHistoryView(
            { entries.values.toList() }, { entries[it] }, { context },
            { entry, part ->
                val info = NetworkHistoryView.bodyOf(entry, part)
                val raw = info?.let { rawBody(epochOf.getValue(entry.id), entry.id, part) }
                raw?.let { BodyCodec.decode(it, info.encoding).let { d -> NetworkFilterEval.BodyBytes(d.bytes, d.decoded) } }
            },
            { entry, part -> decodedJson(entry, part, epochOf[entry.id], entries::get) },
            readEnvelope,
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
    private suspend fun sync(app: AppConnection): Boolean {
        val identity = DtaJson.decodeFromJsonElement(JournalIdentityResult.serializer(), app.request("network_identity"))
        archiving = identity.persisted
        if (!identity.persisted) return false
        context = identity.context
        deviceEntries = identity.entries
        var current = currentEpoch(app, identity)
        val saved = store.cursor(device.key, packageName)
        // Feature 068: subscribed to this journal, the feed brings what is written; no page is read.
        if (feedSub != null) {
            if (saved?.journalId == identity.journalId) { lag = 0; return true }
            feedSub = null
        }
        var position = saved?.position
        var lastId = saved?.lastId
        // The first page starts on the line of the last record read, so reading it also checks it (feature 046).
        var check = previousLine(position)
        repeat(catchUpBudgetPages) {
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
                when (continuity(app, line, lastId, records.firstOrNull()?.id, identity)) {
                    Continuity.SAME -> records = records.drop(1)
                    Continuity.GOES_ON -> if (records.isEmpty() && next == line) next = position!!
                    Continuity.ROLLED_BACK -> {
                        current = reopen(app, current, identity)
                        position = null
                        lastId = null
                        return@repeat
                    }
                }
            }
            if (records.isNotEmpty()) {
                store.putEntries(current.id, records)
                // Feature 068: small bodies come with their records; nothing is asked for them.
                storeBodies(current.id, page.bodies)
                putLive(current.id, records)
                lastId = records.last().id
            }
            position = next
            store.setCursor(device.key, packageName, identity.journalId, position, lastId)
            catchingUpNow = if (page.end) 0 else (identity.entries - live.size).toLong().coerceAtLeast(1)
            if (page.end) {
                lag = 0
                acknowledge(app)
                return true
            }
        }
        lag = (identity.entries - live.size).toLong().coerceAtLeast(0)
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

    val feedLive: Boolean get() = feedSub != null

    /**
     * Feature 068 (research R1): reads the journal to its end by pages, then subscribes from there;
     * from then on the app sends each record as it writes it ([apply]). Under the mutex, so a batch
     * that arrives before the answer waits for it. Null when there is no journal or it is not read
     * to its end yet.
     */
    suspend fun openFeed(): io.yamsergey.dta.protocol.network.FeedOpenResult? = mutex.withLock {
        val app = connection()
        feedSub = null
        if (!sync(app) || lag > 0) return null
        val saved = store.cursor(device.key, packageName) ?: return null
        val params = DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.FeedOpenParams.serializer(),
            io.yamsergey.dta.protocol.network.FeedOpenParams(saved.journalId, saved.position ?: "0:0")).jsonObject
        val result = DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.FeedOpenResult.serializer(), app.request("network_feed_open", params))
        feedSub = result.subscription
        result
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
            store.putEntries(current.id, batch.records)
            storeBodies(current.id, batch.bodies)
            putLive(current.id, batch.records)
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
    private suspend fun continuity(app: AppConnection, line: String, lastId: String?, first: String?, identity: JournalIdentityResult): Continuity {
        val coverage = identity.context.coverage
        // Feature 066: entries the device removed after archiving are gone the same way as evicted ones.
        val removed = coverage.evicted + (coverage.archivedOnly?.values?.sum() ?: 0)
        if (lastId == null) {
            // No record was read at this position: only one beyond the journal shows.
            return if (first == null && removed == 0) Continuity.ROLLED_BACK else Continuity.GOES_ON
        }
        if (first == lastId) return Continuity.SAME
        // Feature 066: the device removed what this computer archived up to there; a missing line is that, not a rollback.
        coverage.archivedThrough?.let { through -> if (cursorAtOrBefore(line, through)) return Continuity.GOES_ON }
        val time = live[lastId]?.timeMs
        val evicted = removed > 0 && (coverage.earliestMs == null || time == null || time <= coverage.earliestMs!!)
        if (evicted) return Continuity.GOES_ON
        // That line was cut when read (being written, or a crash): the record is one line earlier.
        val earlier = previousLine(line) ?: return Continuity.ROLLED_BACK
        val params = buildJsonObject {
            put("after", earlier)
            put("limit", 1)
        }
        val before = DtaJson.decodeFromJsonElement(JournalPageResult.serializer(), app.request("network_journal", params))
        return if (before.records.firstOrNull()?.id == lastId) Continuity.GOES_ON else Continuity.ROLLED_BACK
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
        val known = epoch ?: store.liveEpoch(device.key, packageName)?.also { e ->
            resetLive()
            putLive(e.id, store.entries(e.id), emit = false)
        }
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

    private fun pending(): List<PendingBody> {
        val order = live.keys.withIndex().associate { it.value to it.index }
        return pendingCopies.values.sortedWith(compareBy({ order[it.entryId] ?: Int.MAX_VALUE }, { it.part.ordinal }))
    }

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
        if (!pendingCopies.containsKey(key) || !copying.add(key)) return
        try {
            copyOne(e, entryId, part, paced)
        } finally {
            copying.remove(key)
        }
    }

    private suspend fun copyOne(e: Epoch, entryId: String, part: BodyPart, paced: suspend (bytes: Long, ms: Long) -> Unit) {
        // A body all in its preview (a small text message): archived from the record; the device keeps no file for it.
        live[entryId]?.let { NetworkHistoryView.bodyOf(it, part)?.wholeText() }?.let { text ->
            text.toByteArray(Charsets.UTF_8).inputStream().use { store.putBody(e.id, entryId, part, it) }
            pendingCopies.remove(entryId to part)
            changeSeq[entryId] = nextSeq()
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
            pendingCopies.remove(entryId to part)
            changeSeq[entryId] = nextSeq()
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
            pendingCopies.remove(entryId to part)
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
