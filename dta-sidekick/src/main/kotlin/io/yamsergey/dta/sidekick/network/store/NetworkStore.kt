package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.ArchiveAck
import io.yamsergey.dta.protocol.network.ArchiveAckResult
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HistoryDefaults
import io.yamsergey.dta.protocol.network.HistorySettings
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.ProcessRun
import io.yamsergey.dta.protocol.network.RunEnd
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.sidekick.network.BodySink
import kotlinx.serialization.Serializable
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.Writer
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** One line of the segment log: the latest record per id wins on load. */
@Serializable
internal data class EntryRecord(val entry: NetworkEntry)

/** What the log alone cannot tell: how far eviction went, when history was cleared, which journal this is. */
@Serializable
internal data class StoreState(
    /** runId → highest evicted seq of that run. */
    val evictedThrough: Map<String, Long> = emptyMap(),
    val evicted: Int = 0,
    val clearedAtMs: Long? = null,
    /** Changes when the history is cleared or the storage is new (feature 011, contracts/sidekick-journal.md). */
    val journalId: String? = null,
    /** Feature 066: the keep-on-device budget and warning size; null is the default. */
    val keepBytes: Long? = null,
    val warnBytes: Long? = null,
    /** The last acknowledgement accepted for this journal: what a computer has archived. */
    val ack: io.yamsergey.dta.protocol.network.ArchiveAck? = null,
    /** Entries removed after a computer archived them, by that computer's id (at most 4 ids). */
    val archivedOnly: Map<String, Int> = emptyMap(),
)

/** The journal's identity; [reason] says why it is new in this process (`cleared`, `storage-new`). */
data class JournalIdentity(val journalId: String, val reason: String?, val entries: Int, val persisted: Boolean)

/**
 * A page of the journal: records in write order; [next] resumes after them; [end] when nothing more
 * is written yet; [remaining] lines are after [next] (feature 068: how far the reader is behind).
 */
data class JournalPage(val journalId: String, val records: List<NetworkEntry>, val next: String, val end: Boolean, val remaining: Int = 0)

/**
 * Storage bounds (data-model §Bounds); stated in the capability and coverage. None by default:
 * the whole history and every body are kept. A set [historyBytes] evicts the oldest entries; a
 * set [bodyBytes] stores a body up to it and states the rest as truncated.
 */
data class StoreBounds(
    val historyBytes: Long? = null,
    val bodyBytes: Long? = null,
    val segmentBytes: Long = 4L * 1024 * 1024,
)

/** A page of the store, before redaction or presentation. */
data class StorePage(
    val entries: List<NetworkEntry>,
    val total: Int,
    val next: String?,
    val searched: Int,
)

/**
 * Network history that outlives the process (US5): an append-only JSON-lines segment log
 * (the journal) and one file per body, written by one background thread so capture never
 * waits on disk metadata.
 *
 * Memory stays flat however long the app records (feature 011 US1): only in-flight entries
 * and a window of the most recently finished ones are held ([recentWindow]); an older entry
 * is read back from the journal when something still updates it. The host reads the journal
 * ([journal], [identity]) and answers queries; [list] over the whole journal remains for the
 * memory-only fallback and as the reference the host is checked against.
 *
 * On start, in-flight entries of earlier runs are written again as `process-ended`. Over
 * [StoreBounds.historyBytes] the oldest entries and their bodies are evicted, the count kept
 * in coverage; a persisted watermark keeps evicted entries from coming back.
 *
 * Every failure degrades instead of throwing: an unusable directory or a write error turns
 * persistence off (`coverage.persisted = false`, everything then stays in memory); a full
 * writer queue counts the record as dropped (constitution I, III).
 */
class NetworkStore(
    storage: StorageDir?,
    pid: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = 10_000,
    startWriter: Boolean = true,
    val bounds: StoreBounds = StoreBounds(),
    /** Finished entries held in memory besides the in-flight ones. */
    private val recentWindow: Int = 2_000,
    /** Runs on the writer thread just before the earlier runs are read (tests hold the read with it). */
    private val beforeLoad: () -> Unit = {},
    /** Feature 066: where the start warning goes when the history is over its warning size (the device log). */
    private val warn: (String) -> Unit = {},
    /**
     * Where a trim pass's body files are deleted: a thread of their own, so the writer goes on
     * writing traffic (found under load, 2026-11-19: deleting tens of thousands of small files on
     * the writer thread filled the queue and records were dropped).
     */
    bodyDeleter: ((Runnable) -> Unit)? = null,
    /** Whether the body directory (its size on disk, its files) is to be made anew at start ([Companion.bodiesDirTooLarge]). */
    private val bodiesDirTooLarge: (dirBytes: Long, files: Int) -> Boolean = Companion::bodiesDirTooLarge,
) {
    private val storage: StorageDir? = storage?.takeIf { it.prepare() }

    val startedAtMs: Long = clock()
    val runId: String = "r$startedAtMs-$pid"
    private val pid = pid
    private val seq = AtomicLong()

    private val lock = Any()
    /** In-flight entries and the recent window, in insertion order; everything when not persisted. */
    private val entries = LinkedHashMap<String, NetworkEntry>()
    private var finishedInMemory = 0
    private var dropped = 0
    private var state = StoreState()
    private var journalReason: String? = null

    /** Entries stored (not evicted), their earliest time, and each run's last time: kept as counters. */
    private var stored = 0
    private var earliestMs: Long? = null
    private val runLastTime = LinkedHashMap<String, Long>()
    private var highestCreated = 0L

    @Volatile
    private var persisted = this.storage != null

    /** A record waiting for the writer, with when the store took it (feature 068: how long it waited). */
    private class Queued(val record: EntryRecord, val atMs: Long)
    private val queue = ArrayBlockingQueue<Queued>(queueCapacity)
    private val writeLock = Any()
    private var writer: Thread? = null
    private var segment: File? = null
    private var segmentWriter: Writer? = null
    /** Bytes written to [segment] (its file length lags behind the buffered writer). */
    private var segmentBytes = 0L
    /** Lines written to [segment]: a record's position is `segment:line` after it (feature 068). */
    private var segmentLines = 0
    /** Feature 068: lines of each segment, counted once for those of earlier processes; for [JournalPage.remaining]. */
    private val segmentLineCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Feature 068: who gets each record once it is on disk, and those written but not flushed yet (under [writeLock]). */
    private var liveListener: ((NetworkEntry, String, Long) -> Unit)? = null
    private val unflushed = ArrayList<Triple<NetworkEntry, String, Long>>()
    private var unflushedSinceMs = 0L
    private var liveEnded: (() -> Unit)? = null
    /** Highest segment index ever used; indices never repeat, so file order is time order. */
    private var lastSegmentIndex = 0
    /** Per segment: the newest entry id recorded in it (all older ones are older). */
    private val segmentNewest = LinkedHashMap<File, String>()
    private val diskBytes = AtomicLong()

    /** Bytes of body files a trim pass gave to [bodyDeleter] and not deleted yet: counted gone already. */
    private val deleting = AtomicLong()

    /**
     * The body files there are, by name, with what each takes on disk (0 while being written). Only
     * these are looked at or deleted: a file looked for and not there costs a directory lookup, and
     * on the emulator (2026-11-20) a trim pass looking for every part of every entry kept a thread
     * at 100% CPU in the kernel, the directory being 20 MB with no file in it.
     */
    private val bodyFiles = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val deletes = AtomicLong()
    private val ownDeleter = bodyDeleter == null
    private val bodyDeleter: (Runnable) -> Unit = bodyDeleter ?: run {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dta-network-bodies").apply { isDaemon = true } }
        val run: (Runnable) -> Unit = { pool.execute(it) }
        run
    }

    /** What the history takes once the body files being deleted are gone: what the budget is held to. */
    private fun occupied(): Long = diskBytes.get() - deleting.get()

    /** Counts not written yet, and when each entry's record was last written by [upsertCount] (under [lock]). */
    private val deferred = HashMap<String, NetworkEntry>()
    private val journaledAt = HashMap<String, Long>()
    private var deferredCheckedMs = 0L
    /** Feature 066: the ids each segment holds (under [writeLock]), to find an entry's latest record. */
    private val segmentIds = LinkedHashMap<File, SegmentIds>()
    private val runOrdinals = HashMap<String, Int>()
    private val runNames = ArrayList<String>()
    private val stateFileLock = Any()
    /** Open once the earlier runs are read (at once without storage). */
    private val loaded = java.util.concurrent.CountDownLatch(1)

    /**
     * Only the small state file is read here: the store is made during the app's start, on its
     * main thread. The earlier runs' journal (it can be gigabytes) is read on the writer thread,
     * under [writeLock], so nothing is written before the last segment's index is known; capture
     * meanwhile only queues. Queries that need the counters wait for it ([awaitLoaded]).
     */
    init {
        if (persisted) readState() else newJournal("storage-new")
        when {
            !persisted -> loaded.countDown()
            startWriter -> writer = thread(isDaemon = true, name = "dta-network-store") {
                try {
                    synchronized(writeLock) {
                        beforeLoad()
                        load()
                    }
                } catch (_: InterruptedException) {
                    return@thread
                } finally {
                    loaded.countDown()
                }
                warnIfLarge()
                // What arrived while reading was kept whole; the window applies again from here.
                synchronized(lock) { trimWindow() }
                writerLoop()
            }
            else -> {
                load()
                loaded.countDown()
                warnIfLarge()
            }
        }
    }

    /** Waits until the earlier runs are read; false when [timeoutMs] passed first. */
    fun awaitLoaded(timeoutMs: Long = LOAD_WAIT_MS): Boolean =
        runCatching { loaded.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)

    fun nextId(): String = "$runId:${seq.incrementAndGet()}"

    fun upsert(entry: NetworkEntry): EntryChange {
        val change = remember(entry) ?: return EntryChange.UPDATED
        synchronized(lock) {
            // A full record supersedes a count not written yet; a finished entry's count never changes again.
            deferred.remove(entry.id)
            if (!inFlight(entry)) journaledAt.remove(entry.id)
        }
        enqueue(EntryRecord(entry))
        return change
    }

    /**
     * An open socket's message count changed and nothing else (found under load, 2026-11-19: a
     * record of the whole connection per message was four fifths of the journal). Memory has it
     * at once; the journal at most once per [DEFER_MS], the last count written by the writer a
     * second later or by [flush].
     */
    fun upsertCount(entry: NetworkEntry): EntryChange {
        val change = remember(entry) ?: return EntryChange.UPDATED
        val now = clock()
        val write = synchronized(lock) {
            val at = journaledAt[entry.id]
            if (at == null || now - at >= DEFER_MS) {
                journaledAt[entry.id] = now
                deferred.remove(entry.id)
                true
            } else {
                deferred[entry.id] = entry
                false
            }
        }
        if (write) enqueue(EntryRecord(entry))
        return change
    }

    /** Writes the deferred counts due now, or all of them when [all]. */
    private fun writeDeferred(all: Boolean) {
        val due = synchronized(lock) {
            if (deferred.isEmpty()) return
            val now = clock()
            val ready = deferred.values.filter { all || now - (journaledAt[it.id] ?: 0) >= DEFER_MS }
            ready.forEach { deferred.remove(it.id); journaledAt[it.id] = now }
            ready
        }
        due.forEach { enqueue(EntryRecord(it)) }
    }

    internal fun writeDeferredForTest() = writeDeferred(all = false)

    internal fun flushQueuedForTest() = flushQueued()

    /** The entry in memory; null when it was evicted. */
    private fun remember(entry: NetworkEntry): EntryChange? {
        return synchronized(lock) {
            if (isEvicted(entry.id)) return null
            val created = isNew(entry.id)
            val previous = entries.remove(entry.id)
            if (previous != null && !inFlight(previous)) finishedInMemory--
            entries[entry.id] = entry
            if (!inFlight(entry)) finishedInMemory++
            if (created) {
                stored++
                if (earliestMs == null) earliestMs = entry.timeMs
            }
            runLastTime[entry.runId] = maxOf(runLastTime[entry.runId] ?: 0, entry.timeMs)
            trimWindow()
            if (created) EntryChange.CREATED else EntryChange.UPDATED
        }
    }

    /** The entry by id: from memory, else (when it left the recent window) from the journal. */
    fun get(id: String): NetworkEntry? {
        synchronized(lock) {
            entries[id]?.let { return it }
            if (!persisted || isEvicted(id)) return null
        }
        // The live run's entries stay in memory until the earlier runs are read (trimWindow), so
        // capture never waits here; an earlier run's entry is asked for by a host, which may wait.
        if (reading() && idParts(id)?.first == runId) return null
        return fromJournal(id)?.let { if (it.runId != runId && inFlight(it)) endedByProcessDeath(it) else it }
    }

    /** Entries held in memory now: in flight plus the recent window (everything without storage). */
    fun inMemory(): Int = synchronized(lock) { entries.size }

    /** The oldest stored entry: the one next to the evicted range. */
    fun earliestId(): String? = snapshot().firstOrNull()?.id

    /**
     * Every stored entry in chronological order. With storage this reads the whole journal
     * (the memory-only fallback and the host's reference use it; the host keeps its own index).
     */
    fun snapshot(): List<NetworkEntry> {
        if (!persisted) return synchronized(lock) { entries.values.toList() }
        flush()
        val all = LinkedHashMap<String, NetworkEntry>()
        forEachRecord { _, entry -> if (!isEvicted(entry.id)) all[entry.id] = entry }
        synchronized(lock) { entries.values.forEach { e -> if (!isEvicted(e.id)) all[e.id] = e } }
        return all.values.toList()
    }

    /**
     * Entries matching [predicate], in [order], starting after cursor [before] (the `next`
     * of a previous page). [StorePage.total] counts all matches.
     */
    fun list(
        order: Order = Order.NEWEST_FIRST,
        limit: Int,
        before: String? = null,
        predicate: (NetworkEntry) -> Boolean = { true },
    ): StorePage {
        val all = snapshot().let { if (order == Order.NEWEST_FIRST) it.asReversed() else it }
        val start = if (before == null) 0 else {
            val at = all.indexOfFirst { it.id == before }
            if (at < 0) throw InvalidParamsException("unknown cursor '$before'; it may have been evicted or cleared")
            at + 1
        }
        var total = 0
        val page = ArrayList<NetworkEntry>(minOf(limit, all.size))
        var more = false
        all.forEachIndexed { index, entry ->
            if (!predicate(entry)) return@forEachIndexed
            total++
            if (index < start) return@forEachIndexed
            if (page.size < limit) page += entry else more = true
        }
        return StorePage(page, total, if (more) page.last().id else null, all.size)
    }

    fun coverage(): Coverage = synchronized(lock) {
        Coverage(
            searched = stored,
            stored = stored,
            earliestMs = earliestMs,
            evicted = state.evicted,
            dropped = dropped,
            persisted = persisted,
            runs = runs(),
            clearedAtMs = state.clearedAtMs,
            deviceReading = reading().takeIf { it },
            deviceBytes = diskBytes.get().takeIf { persisted },
            keepBytes = keepBytes(),
            warnBytes = warnBytes(),
            trimHeld = trimHeld(),
            lastArchivedMs = state.ack?.atMs,
            archivedThrough = state.ack?.through,
            archivedOnly = state.archivedOnly.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Feature 066 (research R7): once, after the earlier runs are read, a history over its warning
     * size is said in the device log with its size, the last archiving and what to do.
     */
    private fun warnIfLarge() {
        val (warnAt, ack) = synchronized(lock) { warnBytes() to state.ack }
        val bytes = diskBytes.get()
        if (!persisted || bytes <= warnAt) return
        val mb = 1024 * 1024
        val since = ack?.atMs?.let {
            "nothing archived since " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(it))
        } ?: "no DTA client has archived it yet"
        runCatching {
            warn("DTA network history on this device is ${bytes / mb} MB, over its ${warnAt / mb} MB warning size: $since. " +
                "Connect a DTA client so it is archived and trimmed, clear it (dta network clear), or raise the budget (dta network history set).")
        }
    }

    /** Why the history stays over its budget, or null (research R2 hold reasons). */
    private fun trimHeld(): String? {
        if (!persisted || occupied() <= keepBytes()) return null
        val ack = state.ack ?: return "no-archive"
        return if (clock() - (ack.atMs ?: 0) > STALE_ACK_MS) "stale-archive" else "unarchived"
    }

    /**
     * Feature 066 (research R1): a computer has archived the journal before [ArchiveAck.through],
     * except the bodies of [ArchiveAck.keep]. Kept in the state file; the writer trims to it.
     */
    fun acceptAck(ack: ArchiveAck): ArchiveAckResult {
        if (!persisted) return ArchiveAckResult(false, "not-persisted")
        if (reading()) return ArchiveAckResult(false, "reading")
        val cursor = runCatching { parseCursor(ack.through) }.getOrNull() ?: return ArchiveAckResult(false, "invalid-cursor")
        synchronized(lock) {
            if (ack.journalId != state.journalId) return ArchiveAckResult(false, "journal-changed")
            val previous = state.ack?.let { runCatching { parseCursor(it.through) }.getOrNull() }
            val through = if (previous != null && compareValuesBy(previous, cursor, { it.first }, { it.second }) > 0) state.ack!!.through else ack.through
            state = state.copy(ack = ack.copy(through = through, atMs = clock()))
        }
        saveState()
        return ArchiveAckResult(true, trimPending = occupied() > synchronized(lock) { keepBytes() })
    }

    /** Tests: a warning size below the 16 MB a person may set, kept in the state file. */
    internal fun setWarnForTest(warnBytes: Long) {
        synchronized(lock) { state = state.copy(warnBytes = warnBytes) }
        saveState()
    }

    /** Tests: a budget below the 16 MB a person may set. */
    internal fun setBudgetForTest(keepBytes: Long) = synchronized(lock) { state = state.copy(keepBytes = keepBytes) }

    /** Tests: writes what is queued and runs a trim pass now. */
    internal fun trimNow() {
        flush()
        trimArchived()
        awaitBodyDeletesForTest()
    }

    /** Tests: waits until the body files given to the deleting thread are deleted. */
    internal fun awaitBodyDeletesForTest() {
        if (!ownDeleter) return
        val done = java.util.concurrent.CountDownLatch(1)
        bodyDeleter(Runnable { done.countDown() })
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun segmentIdsForTest(): Map<String, List<String>> = synchronized(writeLock) {
        segmentIds.entries.associate { (file, ids) -> file.name to ids.keys().map { k -> "${runNames[(k ushr SEQ_BITS).toInt()]}:${k and SEQ_MASK}" } }
    }

    internal fun segmentIndexBytesForTest(): Long = synchronized(writeLock) { segmentIds.values.sumOf { it.capacityBytes() } }

    /**
     * Removes whole segments before the acknowledged one, oldest first, while the history is over
     * its budget (research R2). An entry whose latest record is in such a segment either leaves
     * the device with its bodies (archived), or, in flight or with bodies not yet copied, has
     * that record written again at the end of the journal. Runs on the writer thread.
     */
    fun trimArchived() {
        if (!persisted || reading()) return
        val (ack, keep) = synchronized(lock) { (state.ack ?: return) to keepBytes() }
        if (occupied() <= keep) return
        val (throughSegment, throughLine) = runCatching { parseCursor(ack.through) }.getOrNull() ?: return
        val keepIds = ack.keep.toHashSet()
        val candidates = synchronized(writeLock) {
            // The computer has read into the segment being written: the next records go to a new
            // one, so this one can go once it is read to its end (bodies are large, records small:
            // a whole history's records can sit in one segment).
            if (segment != null && segmentIndex(segment!!) == throughSegment && throughLine > 0) {
                runCatching { segmentWriter?.close() }
                segmentIds[segment!!]?.compact()
                segmentWriter = null
                segment = null
            }
            segmentIds.keys.filter { it != segment && (segmentIndex(it) ?: Int.MAX_VALUE) <= throughSegment }
                .sortedBy { segmentIndex(it) }
        }
        if (candidates.isEmpty()) return
        var trimmed = false
        // One segment at a time, the writer lock held only to decide and remove: a large history
        // has many segments and many bodies, and traffic is written and the journal read meanwhile.
        for (file in candidates) {
            if (occupied() <= keep) break
            val index = segmentIndex(file) ?: continue
            // A closed segment is never written again: it is read with no lock held.
            val records = ArrayList<NetworkEntry>()
            var lines = 0
            val read = runCatching {
                file.forEachLine { line ->
                    lines++
                    if (line.isBlank()) return@forEachLine
                    runCatching { DtaJson.decodeFromString(EntryRecord.serializer(), line).entry }.getOrNull()?.let { records += it }
                }
            }
            if (read.isFailure) break
            // The cursor's own segment only when the computer read all of it.
            if (index == throughSegment && lines > throughLine) break
            val bodies = ArrayList<Pair<File, Long>>()
            val gone = synchronized(writeLock) {
                // Cleared meanwhile: nothing of it is left to remove.
                if (file !in segmentIds) return@synchronized false
                val latest = LinkedHashMap<String, NetworkEntry>()
                records.forEach { if (!isEvicted(it.id)) latest[it.id] = it }
                val later = segmentIds.entries.filter { (f, _) -> (segmentIndex(f) ?: 0) > index }.map { it.value }
                var removed = 0
                for ((id, entry) in latest) {
                    val key = keyOf(id)
                    if (key != null && later.any { it.contains(key) }) continue
                    val held = synchronized(lock) { entries[id] }
                    if (inFlight(held ?: entry) || id in keepIds) {
                        write(EntryRecord(held ?: entry))
                        continue
                    }
                    // Every part kind: a body file can exist before its entry says so; only files there are.
                    for (part in BodyPart.entries) {
                        val file = existingBody(id, part) ?: continue
                        bodies += file to (bodyFiles[file.name] ?: 0L)
                    }
                    synchronized(lock) { entries.remove(id)?.let { if (!inFlight(it)) finishedInMemory-- } }
                    removed++
                }
                flushWriter()
                val size = file.length()
                if (file.delete()) diskBytes.addAndGet(-size)
                segmentIds.remove(file)
                segmentNewest.remove(file)
                synchronized(lock) {
                    stored -= removed
                    val counts = state.archivedOnly.toMutableMap()
                    val host = if (ack.hostId in counts || counts.size < MAX_HOSTS) ack.hostId else "other"
                    counts[host] = (counts[host] ?: 0) + removed
                    state = state.copy(archivedOnly = counts)
                }
                true
            }
            if (!gone) break
            trimmed = true
            // The bodies of the entries removed: their records are gone, nothing reads them any
            // more. Deleted on their own thread, counted gone now; a process killed before that
            // leaves them to the sweep at start.
            deleteBodies(bodies)
            betweenSegmentsForTest?.invoke()
        }
        if (!trimmed) return
        synchronized(writeLock) {
            synchronized(lock) { earliestMs = firstRecordTime() }
            saveState()
        }
    }

    private fun deleteBodies(bodies: List<Pair<File, Long>>) {
        if (bodies.isEmpty()) return
        val counted = bodies.sumOf { it.second }
        val generation = deletes.get()
        deleting.addAndGet(counted)
        bodyDeleter(Runnable {
            for ((body, size) in bodies) {
                // A clear meanwhile deleted it and started the count over.
                if (deletes.get() != generation) break
                if (body.delete()) diskBytes.addAndGet(-size)
                bodyFiles.remove(body.name)
            }
            // A clear meanwhile started the count over.
            if (deletes.get() == generation) deleting.addAndGet(-counted)
        })
    }

    /** Tests: called between two segments of a trim pass, with no lock held. */
    @Volatile
    internal var betweenSegmentsForTest: (() -> Unit)? = null

    /** The time of the oldest record still in the journal. */
    private fun firstRecordTime(): Long? {
        for (file in segmentFiles()) {
            val time = runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.firstNotNullOfOrNull { line -> runCatching { DtaJson.decodeFromString(EntryRecord.serializer(), line).entry }.getOrNull()?.takeUnless { isEvicted(it.id) }?.timeMs }
                }
            }.getOrNull()
            if (time != null) return time
        }
        return null
    }

    /** `run:seq` → one long: the run's ordinal in the high bits. */
    private fun keyOf(id: String): Long? {
        val (run, n) = idParts(id) ?: return null
        val ordinal = runOrdinals.getOrPut(run) { runNames += run; runNames.size - 1 }
        return (ordinal.toLong() shl SEQ_BITS) or (n and SEQ_MASK)
    }

    private fun indexRecord(file: File, id: String) {
        val key = keyOf(id) ?: return
        segmentIds.getOrPut(file) { SegmentIds() }.add(key)
    }

    private fun keepBytes() = state.keepBytes ?: HistoryDefaults.KEEP_BYTES
    private fun warnBytes() = state.warnBytes ?: HistoryDefaults.WARN_BYTES

    /** Feature 066: the keep-on-device budget and warning size in force, with the history's size. */
    fun settings(): HistorySettings = synchronized(lock) {
        HistorySettings(keepBytes(), warnBytes(), deviceBytes = diskBytes.get().takeIf { persisted })
    }

    /** Sets either or both; kept in the state file. Refused (nothing changes) below 16 MB or with the warning under the budget. */
    fun setSettings(keepBytes: Long? = null, warnBytes: Long? = null): HistorySettings {
        synchronized(lock) {
            val keep = keepBytes ?: keepBytes()
            val warn = warnBytes ?: warnBytes()
            HistoryDefaults.problem(keep, warn)?.let { throw InvalidParamsException(it) }
            state = state.copy(keepBytes = keep, warnBytes = warn)
        }
        synchronized(writeLock) { saveState() }
        return settings()
    }

    /** Still reading the earlier runs: the counters are not complete, nothing is written yet. */
    private fun reading(): Boolean = loaded.count > 0

    /** Feature 068: the earlier runs are still being read; nothing can be fed until they are. */
    val isReading: Boolean get() = reading()

    /** Feature 068: the history is in a journal (else it is kept in memory only). */
    val isPersisted: Boolean get() = persisted

    /** Which journal this is, and why it is new in this process (feature 011). */
    fun identity(): JournalIdentity = synchronized(lock) { JournalIdentity(state.journalId!!, journalReason, stored, persisted) }

    /**
     * Journal records after [after] (a [JournalPage.next]; null from the start), at most [limit],
     * in write order. The latest record of an entry is its current state.
     */
    fun journal(after: String?, limit: Int): JournalPage {
        val journalId = synchronized(lock) { state.journalId!! }
        if (!persisted) return JournalPage(journalId, emptyList(), after ?: "0:0", end = true)
        flush()
        val (fromSegment, fromLine) = after?.let(::parseCursor) ?: (0 to 0)
        val records = ArrayList<NetworkEntry>()
        var next = after ?: "0:0"
        var end = true
        val files = segmentFiles()
        for (file in files) {
            val index = segmentIndex(file) ?: continue
            if (index < fromSegment) continue
            var line = 0
            val skip = if (index == fromSegment) fromLine else 0
            var full = false
            file.bufferedReader().useLines { lines ->
                for (text in lines) {
                    if (line < skip) { line++; continue }
                    if (records.size >= limit) { full = true; break }
                    line++
                    if (text.isBlank()) continue
                    runCatching { DtaJson.decodeFromString(EntryRecord.serializer(), text).entry }.getOrNull()
                        ?.takeUnless { isEvicted(it.id) }?.let(records::add)
                }
            }
            next = "$index:$line"
            if (full) { end = false; break }
        }
        return JournalPage(journalId, records, next, end, if (end) 0 else linesAfter(next, files))
    }

    private fun linesAfter(cursor: String, files: List<File>): Int {
        val (atSegment, atLine) = parseCursor(cursor)
        return files.sumOf { file ->
            val index = segmentIndex(file) ?: return@sumOf 0
            if (index < atSegment) return@sumOf 0
            val lines = segmentLineCounts.getOrPut(index) { runCatching { file.bufferedReader().useLines { it.count() } }.getOrDefault(0) }
            if (index == atSegment) (lines - atLine).coerceAtLeast(0) else lines
        }
    }

    /** Null when bodies cannot be stored (memory-only history). */
    fun openBodySink(id: String, part: BodyPart): BodySink? {
        if (!persisted) return null
        val file = bodyFile(id, part) ?: return null
        // Known before the file is made: a trim pass that removes the entry meanwhile deletes it too.
        bodyFiles[file.name] = bodyFiles[file.name] ?: 0L
        return try {
            val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
            object : BodySink {
                private var written = 0L
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    out.write(buffer, offset, length)
                    written += length
                }
                override fun close() {
                    out.close()
                    val size = onDisk(written)
                    diskBytes.addAndGet(size)
                    bodyFiles[file.name] = size
                }
            }
        } catch (_: Throwable) {
            if (!file.exists()) bodyFiles.remove(file.name)
            null
        }
    }

    fun openBody(id: String, part: BodyPart): InputStream? {
        val file = existingBody(id, part) ?: return inRecord(id, part)?.let { java.io.ByteArrayInputStream(it) }
        return try {
            FileInputStream(file)
        } catch (_: Throwable) {
            null
        }
    }

    /** [length] bytes of a body from [offset] (fewer at its end), so the host copies it in chunks. */
    fun openBodyRange(id: String, part: BodyPart, offset: Long, length: Long): InputStream? {
        val input = openBody(id, part) ?: return null
        return try {
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            BoundedInputStream(input, length)
        } catch (_: Throwable) {
            runCatching { input.close() }
            null
        }
    }

    /** Bytes of a stored body, or null when there is none. */
    fun bodySize(id: String, part: BodyPart): Long? = existingBody(id, part)?.takeIf { it.isFile }?.length() ?: inRecord(id, part)?.size?.toLong()

    /** A body with no file that its entry's preview holds whole (a small text message). */
    private fun inRecord(id: String, part: BodyPart): ByteArray? =
        get(id)?.let { io.yamsergey.dta.protocol.network.NetworkHistoryView.bodyOf(it, part)?.wholeText()?.toByteArray(Charsets.UTF_8) }

    /** Bytes the history occupies on disk: segments plus bodies. */
    fun diskBytes(): Long = diskBytes.get()

    /**
     * Deletes every run's entries, segments and body files; the live run keeps capturing, in
     * a new journal (identity reason `cleared`).
     * @return how many entries were removed.
     */
    fun clear(): Int {
        awaitLoaded()
        val removed = synchronized(lock) {
            val count = stored
            entries.clear()
            deferred.clear()
            journaledAt.clear()
            finishedInMemory = 0
            stored = 0
            earliestMs = null
            runLastTime.clear()
            state = StoreState(clearedAtMs = clock(), journalId = UUID.randomUUID().toString(), keepBytes = state.keepBytes, warnBytes = state.warnBytes)
            journalReason = "cleared"
            count
        }
        synchronized(writeLock) {
            queue.clear()
            // Feature 068: a live subscriber's journal is gone; it is told, and nothing more comes.
            unflushed.clear()
            liveListener = null
            liveEnded?.let { runCatching { it() } }
            liveEnded = null
            runCatching { segmentWriter?.close() }
            segmentWriter = null
            segment = null
            segmentNewest.clear()
            segmentIds.clear()
            storage?.segments?.listFiles()?.forEach { it.delete() }
            storage?.bodies?.listFiles()?.forEach { it.delete() }
            bodyFiles.clear()
            storage?.let(::renewBodiesIfLarge)
            deletes.incrementAndGet()
            deleting.set(0)
            diskBytes.set(0)
            saveState()
        }
        return removed
    }

    /** Writes everything queued so far (the writer thread does this continuously). */
    fun flush() {
        // Before the earlier runs are read nothing can be written (the next segment's index is
        // not known yet). A small history is read in milliseconds; a large one is not waited
        // for longer than FLUSH_WAIT_MS: what is queued is written by the writer once it is read.
        if (!awaitLoaded(FLUSH_WAIT_MS)) return
        writeDeferred(all = true)
        flushQueued()
    }

    /** What [startLive] gives: the records written after the position, and the position after them. */
    class LiveStart(val records: List<NetworkEntry>, val to: String)

    /**
     * Feature 068 (research R1): the journal read from [after] and kept open. Under the writer lock,
     * so nothing is written meanwhile, it reads what was written after [after] and registers
     * [listener], which then gets each record with its position once it is on disk, on the writer
     * thread (it must not wait), and when the store took it ([now]'s clock). Null when more than
     * [limit] records follow [after]: read pages first.
     */
    fun startLive(after: String, limit: Int, onEnd: () -> Unit = {}, listener: (NetworkEntry, String, Long) -> Unit): LiveStart? {
        if (!persisted || !awaitLoaded(FLUSH_WAIT_MS)) return null
        synchronized(writeLock) {
            val page = journal(after, limit)
            if (!page.end) return null
            unflushed.clear()
            liveListener = listener
            liveEnded = onEnd
            return LiveStart(page.records, page.next)
        }
    }

    /** Stops [startLive]'s listener. */
    fun stopLive() = synchronized(writeLock) {
        liveListener = null
        liveEnded = null
        unflushed.clear()
    }

    /** Flushes the segment being written and hands what it held to the live listener. */
    private fun flushWriter() {
        runCatching { segmentWriter?.flush() }
        if (unflushed.isEmpty()) return
        val listener = liveListener
        val out = ArrayList(unflushed)
        unflushed.clear()
        if (listener != null) for ((entry, at, takenMs) in out) runCatching { listener(entry, at, takenMs) }
    }

    private fun flushQueued() = synchronized(writeLock) {
        while (true) queue.poll()?.let { write(it.record, it.atMs) } ?: break
        flushWriter()
        Unit
    }

    /** Stops the writer after writing what is queued. */
    fun close() {
        writer?.interrupt()
        writer?.join(1000)
        synchronized(writeLock) {
            flush()
            runCatching { segmentWriter?.close() }
            segmentWriter = null
        }
    }

    /**
     * Evicts the oldest entries (and their bodies) until the history fits its bound, then
     * deletes segments holding only evicted entries. Runs on the writer thread.
     */
    fun evictIfNeeded() {
        val limit = bounds.historyBytes ?: return
        if (diskBytes.get() <= limit) return
        synchronized(writeLock) {
            // Roll the segment: the one being written could never be deleted, and its
            // bytes would make the loop evict entries without freeing anything.
            runCatching { segmentWriter?.close() }
            segmentWriter = null
            segment = null
            val oldestFirst = ArrayDeque<NetworkEntry>()
            val seen = HashSet<String>()
            forEachRecord { _, entry -> if (!isEvicted(entry.id) && seen.add(entry.id)) oldestFirst += entry }
            while (diskBytes.get() > limit) {
                val oldest = oldestFirst.removeFirstOrNull() ?: break
                synchronized(lock) {
                    entries.remove(oldest.id)?.let { if (!inFlight(it)) finishedInMemory-- }
                    val (run, n) = idParts(oldest.id) ?: (oldest.runId to 0L)
                    state = state.copy(
                        evictedThrough = state.evictedThrough + (run to maxOf(n, state.evictedThrough[run] ?: 0)),
                        evicted = state.evicted + 1,
                    )
                    stored--
                    earliestMs = oldestFirst.firstOrNull()?.timeMs
                }
                for (part in BodyPart.entries) {
                    existingBody(oldest.id, part)?.let { file ->
                        val size = bodyFiles[file.name] ?: 0L
                        if (file.delete()) diskBytes.addAndGet(-size)
                        bodyFiles.remove(file.name)
                    }
                }
                deleteEvictedSegments()
            }
            saveState()
        }
    }

    private fun deleteEvictedSegments() {
        val iterator = segmentNewest.entries.iterator()
        while (iterator.hasNext()) {
            val (file, newest) = iterator.next()
            if (file == segment) continue
            if (!isEvicted(newest)) break
            val size = file.length()
            if (file.delete()) diskBytes.addAndGet(-size)
            iterator.remove()
            segmentIds.remove(file)
        }
    }

    // ---- memory ------------------------------------------------------------------------

    private fun inFlight(entry: NetworkEntry): Boolean = when (entry) {
        is HttpEntry -> entry.state == HttpState.PENDING
        is WsConnectionEntry -> entry.state == WsState.OPEN || entry.state == WsState.CONNECTING
        else -> false
    }

    /** Only this process creates entries, with growing sequence numbers. */
    private fun isNew(id: String): Boolean {
        val (run, n) = idParts(id) ?: return !entries.containsKey(id)
        if (run != runId) return false
        if (n <= highestCreated) return false
        highestCreated = n
        return true
    }

    /** Drops the oldest finished entries beyond the window; they stay in the journal. */
    private fun trimWindow() {
        // Not while the earlier runs are read: the journal is not written then, so [get] must find
        // the live run's entries in memory.
        if (!persisted || finishedInMemory <= recentWindow || reading()) return
        val iterator = entries.values.iterator()
        while (finishedInMemory > recentWindow && iterator.hasNext()) {
            val e = iterator.next()
            if (!inFlight(e)) {
                iterator.remove()
                finishedInMemory--
            }
        }
    }

    /** The latest record of [id] in the journal (newest segments first). */
    private fun fromJournal(id: String): NetworkEntry? {
        flush()
        val marker = "\"id\":\"$id\""
        for (file in segmentFiles().asReversed()) {
            var found: NetworkEntry? = null
            file.forEachLine { line ->
                if (marker in line) {
                    runCatching { DtaJson.decodeFromString(EntryRecord.serializer(), line).entry }.getOrNull()
                        ?.takeIf { it.id == id }?.let { found = it }
                }
            }
            found?.let { return it }
        }
        return null
    }

    private fun forEachRecord(action: (File, NetworkEntry) -> Unit) {
        for (file in segmentFiles()) {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { DtaJson.decodeFromString(EntryRecord.serializer(), line).entry }.getOrNull()?.let { action(file, it) }
            }
        }
    }

    // ---- loading ---------------------------------------------------------------------

    /** The journal's identity and eviction watermark: one small file, read in the constructor. */
    private fun readState() {
        try {
            val stateFile = stateFile()?.takeIf { it.isFile }
            state = stateFile?.let { DtaJson.decodeFromString(StoreState.serializer(), it.readText()) } ?: StoreState()
            if (stateFile == null || state.journalId == null) newJournal("storage-new")
        } catch (_: Throwable) {
            if (state.journalId == null) newJournal("storage-new")
        }
    }

    /**
     * Counts the earlier runs (on the writer thread, while capture goes on): the counters are
     * kept locally and added under [lock] at the end, the earlier runs' being older than the live run's.
     */
    private fun load() {
        val dir = storage ?: return
        var stored = 0
        var earliestMs: Long? = null
        val runLastTime = LinkedHashMap<String, Long>()
        try {
            // Counted, not held: the entries stay in the journal (feature 011 US1).
            val seen = HashMap<String, java.util.BitSet>()
            val pending = LinkedHashMap<String, NetworkEntry>()
            for (file in segmentFiles()) {
                lastSegmentIndex = maxOf(lastSegmentIndex, segmentIndex(file) ?: 0)
                diskBytes.addAndGet(file.length())
                var newest: String? = null
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val entry = runCatching { DtaJson.decodeFromString(EntryRecord.serializer(), line).entry }.getOrNull()
                        ?: return@forEachLine
                    newest = entry.id
                    indexRecord(file, entry.id)
                    if (isEvicted(entry.id)) return@forEachLine
                    val (run, n) = idParts(entry.id) ?: (entry.runId to 0L)
                    val bits = seen.getOrPut(run) { java.util.BitSet() }
                    if (!bits.get(n.toInt())) {
                        bits.set(n.toInt())
                        stored++
                        if (earliestMs == null) earliestMs = entry.timeMs
                    }
                    runLastTime[entry.runId] = maxOf(runLastTime[entry.runId] ?: 0, entry.timeMs)
                    if (inFlight(entry)) pending[entry.id] = entry else pending.remove(entry.id)
                }
                segmentIds[file]?.compact()
                segmentNewest[file] = newest ?: continue
            }
            // A body whose entry is not in the journal of an earlier run is removed (a trim pass
            // ended by the process being killed between a segment and its bodies); this run's
            // bodies may be written before their records, so they are kept.
            renewBodiesIfLarge(dir)
            dir.bodies.listFiles()?.forEach { body ->
                val (run, n) = bodyId(body.name)?.let(::idParts) ?: (null to 0L)
                if (run != null && run != runId && seen[run]?.get(n.toInt()) != true) body.delete()
                else {
                    val size = onDisk(body.length())
                    diskBytes.addAndGet(size)
                    // A body this run is writing meanwhile is counted when its sink closes.
                    bodyFiles.putIfAbsent(body.name, size)
                }
            }
            // The process that owned them is gone (FR-027): the journal says so for the host too.
            pending.values.forEach { enqueue(EntryRecord(endedByProcessDeath(it))) }
        } catch (_: Throwable) {
            // A history that cannot be read is not a reason to stop capturing.
        } finally {
            synchronized(lock) {
                this.stored += stored
                if (earliestMs != null) this.earliestMs = earliestMs
                runLastTime.forEach { (run, time) -> this.runLastTime[run] = maxOf(this.runLastTime[run] ?: 0, time) }
            }
        }
    }

    private fun newJournal(reason: String) {
        state = state.copy(journalId = UUID.randomUUID().toString())
        journalReason = reason
        saveState()
    }

    /** The process that owned an in-flight entry is gone (FR-027). */
    private fun endedByProcessDeath(entry: NetworkEntry): NetworkEntry = when {
        entry.runId == runId -> entry
        entry is HttpEntry && entry.state == HttpState.PENDING -> entry.copy(state = HttpState.PROCESS_ENDED)
        entry is WsConnectionEntry && (entry.state == WsState.OPEN || entry.state == WsState.CONNECTING) ->
            entry.copy(state = WsState.PROCESS_ENDED)
        else -> entry
    }

    /** Runs from the counters, plus the live run. */
    private fun runs(): List<ProcessRun> {
        val earlier = runLastTime.keys.filter { it != runId }.mapNotNull { id ->
            parseRun(id)?.let { (start, runPid) ->
                ProcessRun(id, runPid, start, endedAtMs = runLastTime[id], ended = RunEnd.PROCESS_ENDED)
            }
        }.sortedBy { it.startedAtMs }
        return earlier + ProcessRun(runId, pid, startedAtMs)
    }

    private fun segmentFiles(): List<File> =
        storage?.segments?.listFiles()?.filter { segmentIndex(it) != null }?.sortedBy { segmentIndex(it) } ?: emptyList()

    // ---- writing ---------------------------------------------------------------------

    private fun enqueue(record: EntryRecord) {
        if (!persisted) return
        if (!queue.offer(Queued(record, clock()))) synchronized(lock) { dropped++ }
    }

    /**
     * Takes and writes each record under [writeLock], so [flush] never returns while a record
     * the writer already took is still unwritten. Capture never waits on this lock: it only
     * offers to the queue, and the writer looks for new records at least every 100 ms.
     */
    private fun writerLoop() {
        while (persisted) {
            try {
                synchronized(writeLock) {
                    val record = queue.poll() ?: run {
                        (writeLock as Object).wait(100)
                        queue.poll()
                    }
                    if (record != null) write(record.record, record.atMs)
                    // Feature 068: a live subscriber gets records once they are on disk, at least every 50 ms or 500 records.
                    if (queue.isEmpty() || unflushed.size >= LIVE_BATCH || (unflushed.isNotEmpty() && clock() - unflushedSinceMs >= LIVE_MS)) flushWriter()
                }
            } catch (_: InterruptedException) {
                return
            }
            val now = clock()
            if (now - deferredCheckedMs >= 100) { deferredCheckedMs = now; runCatching { writeDeferred(all = false) } }
            if (queue.isEmpty()) runCatching { evictIfNeeded() }
            if (queue.isEmpty()) runCatching { trimArchived() }
        }
    }

    /** Now, on the store's clock (feature 068: a feed says how long a record waited). */
    internal fun now(): Long = clock()

    private fun write(record: EntryRecord, takenMs: Long = clock()) {
        if (!persisted) return
        if (synchronized(lock) { isEvicted(record.entry.id) }) return
        try {
            val line = DtaJson.encodeToString(EntryRecord.serializer(), record) + "\n"
            val bytes = line.toByteArray(Charsets.UTF_8).size.toLong()
            writerFor(bytes).write(line)
            segmentBytes += bytes
            segmentLines++
            segmentLineCounts.merge(lastSegmentIndex, 1, Int::plus)
            if (liveListener != null) {
                if (unflushed.isEmpty()) unflushedSinceMs = clock()
                unflushed += Triple(record.entry, "${segmentIndex(segment!!)}:$segmentLines", takenMs)
            }
            diskBytes.addAndGet(bytes)
            segment?.let {
                segmentNewest[it] = newer(segmentNewest[it], record.entry.id)
                indexRecord(it, record.entry.id)
            }
        } catch (_: Throwable) {
            persisted = false
            runCatching { segmentWriter?.close() }
            segmentWriter = null
        }
    }

    private fun writerFor(incoming: Long): Writer {
        val writer = segmentWriter
        if (segment != null && writer != null && (segmentBytes == 0L || segmentBytes + incoming <= bounds.segmentBytes)) return writer
        writer?.close()
        segment?.let { segmentIds[it]?.compact() }
        val file = File(storage!!.segments, "seg-%06d.jsonl".format(++lastSegmentIndex))
        segment = file
        segmentBytes = 0
        segmentLines = 0
        segmentLineCounts[lastSegmentIndex] = 0
        return file.bufferedWriter().also { segmentWriter = it }
    }

    private fun saveState(): Unit = synchronized(stateFileLock) {
        val target = stateFile() ?: return
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(DtaJson.encodeToString(StoreState.serializer(), state))
            tmp.renameTo(target)
        } catch (_: Throwable) {
        }
    }

    // ---- ids -------------------------------------------------------------------------

    private fun isEvicted(id: String): Boolean {
        val (run, n) = idParts(id) ?: return false
        return (state.evictedThrough[run] ?: return false) >= n
    }

    /** Of two ids, the one created later (runs start in time order, seq is monotonic). */
    private fun newer(a: String?, b: String): String {
        if (a == null) return b
        val ka = orderKey(a) ?: return b
        val kb = orderKey(b) ?: return a
        return if (compareValuesBy(ka, kb, { it.first }, { it.second }) >= 0) a else b
    }

    private fun orderKey(id: String): Pair<Long, Long>? {
        val (run, n) = idParts(id) ?: return null
        return (parseRun(run)?.first ?: return null) to n
    }

    private fun idParts(id: String): Pair<String, Long>? {
        val colon = id.lastIndexOf(':')
        if (colon < 0) return null
        return id.substring(0, colon) to (id.substring(colon + 1).toLongOrNull() ?: return null)
    }

    /** `r<startMs>-<pid>` → (startMs, pid). */
    private fun parseRun(runId: String): Pair<Long, Int>? {
        val match = RUN_ID.matchEntire(runId) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[2].toInt()
    }

    private fun parseCursor(cursor: String): Pair<Int, Int> {
        val parts = cursor.split(':')
        val segment = parts.getOrNull(0)?.toIntOrNull()
        val line = parts.getOrNull(1)?.toIntOrNull()
        if (segment == null || line == null) throw InvalidParamsException("unknown journal cursor '$cursor'")
        return segment to line
    }

    private fun bodyFile(id: String, part: BodyPart): File? =
        storage?.let { File(it.bodies, "${id.replace(':', '_')}.${part.name.lowercase()}") }

    /** The body file when there is one ([bodyFiles]): no lookup on disk for a body never written. */
    private fun existingBody(id: String, part: BodyPart): File? = bodyFile(id, part)?.takeIf { bodyFiles.containsKey(it.name) }

    /**
     * ext4 never shrinks a directory: one that held hundreds of thousands of small bodies stays as
     * large with none left, and every lookup in it is slow. Made anew when [bodiesDirTooLarge] says
     * so: the files are moved to a fresh directory (a body being written keeps its open file). Also
     * finishes a renewal a killed process left half done.
     */
    private fun renewBodiesIfLarge(dir: StorageDir) {
        val bodies = dir.bodies
        val fresh = File(dir.root, "bodies.new")
        val old = File(dir.root, "bodies.old")
        runCatching {
            if (!old.exists()) {
                val files = bodies.list()?.size ?: return
                if (!bodiesDirTooLarge(bodies.length(), files)) return
                fresh.deleteRecursively()
                if (!fresh.mkdir()) return
                if (!bodies.renameTo(old)) { fresh.delete(); return }
                if (!fresh.renameTo(bodies)) { old.renameTo(bodies); return }
            } else if (!bodies.isDirectory) {
                if (!(fresh.isDirectory && fresh.renameTo(bodies)) && !bodies.mkdir()) return
            }
            old.listFiles()?.forEach { it.renameTo(File(bodies, it.name)) }
            old.deleteRecursively()
            fresh.deleteRecursively()
        }
    }

    private fun stateFile(): File? = storage?.let { File(it.root, "state.json") }

    /** The entry id of a body file's name (`run_seq.part`, see [bodyFile]). */
    private fun bodyId(name: String): String? {
        val base = name.substringBefore('.')
        val at = base.lastIndexOf('_').takeIf { it > 0 } ?: return null
        return base.substring(0, at) + ":" + base.substring(at + 1)
    }

    /** What a body file takes on the device's disk: whole blocks (a 46-byte message takes one). */
    private fun onDisk(bytes: Long): Long = if (bytes <= 0) 0 else (bytes + BODY_BLOCK - 1) / BODY_BLOCK * BODY_BLOCK

    private fun segmentIndex(file: File): Int? = SEGMENT.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()

    internal companion object {
        /**
         * A body directory is made anew when it takes over 1 MB and more than 256 bytes a file: an
         * ext4 name entry takes well under that, so the rest is blocks left by files long gone.
         */
        fun bodiesDirTooLarge(dirBytes: Long, files: Int): Boolean = dirBytes > (1L shl 20) + files * 256L

        /** The disk block a body file takes at least (ext4 and f2fs on Android: 4 KB). */
        const val BODY_BLOCK = 4096L
        /** A count alone is written at most this often per entry ([upsertCount]). */
        const val DEFER_MS = 1_000L
        /** Feature 068: a live subscriber waits at most this long, or this many records, for a flush. */
        const val LIVE_MS = 50L
        const val LIVE_BATCH = 500
        val RUN_ID = Regex("""r(\d+)-(\d+)""")
        val SEGMENT = Regex("""seg-(\d+)\.jsonl""")
        /** How long a query waits for the earlier runs to be read before answering with what is counted. */
        const val LOAD_WAIT_MS = 30_000L
        /** How long [flush] (a host's read of the journal) waits for the earlier runs: under the host's 10 s. */
        const val FLUSH_WAIT_MS = 2_000L
        /** An acknowledgement older than this, with the history over its budget, is stated as stale. */
        const val STALE_ACK_MS = 3_600_000L
        const val MAX_HOSTS = 4
        const val SEQ_BITS = 40
        const val SEQ_MASK = (1L shl SEQ_BITS) - 1
    }
}

/** At most [remaining] bytes of [input]. */
private class BoundedInputStream(input: InputStream, private var remaining: Long) : java.io.FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }
}

/** The ids (as keys) a segment holds: appended in write order, sorted when first searched. */
private class SegmentIds {
    private var keys = LongArray(16)
    private var size = 0
    private var sorted = true

    fun add(key: Long) {
        if (size == keys.size) keys = keys.copyOf(size * 2)
        if (size > 0 && key < keys[size - 1]) sorted = false
        keys[size++] = key
    }

    fun contains(key: Long): Boolean {
        if (!sorted) { java.util.Arrays.sort(keys, 0, size); sorted = true }
        return java.util.Arrays.binarySearch(keys, 0, size, key) >= 0
    }

    /** Trims the array to its size: the segment is no longer written. */
    fun compact() { if (keys.size > size) keys = keys.copyOf(maxOf(size, 1)) }

    fun keys(): List<Long> = keys.copyOf(size).toList()

    fun capacityBytes(): Long = keys.size * 8L
}
