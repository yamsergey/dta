package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.EntryChange
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
)

/** The journal's identity; [reason] says why it is new in this process (`cleared`, `storage-new`). */
data class JournalIdentity(val journalId: String, val reason: String?, val entries: Int, val persisted: Boolean)

/** A page of the journal: records in write order; [next] resumes after them; [end] when nothing more is written yet. */
data class JournalPage(val journalId: String, val records: List<NetworkEntry>, val next: String, val end: Boolean)

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

    private val queue = ArrayBlockingQueue<EntryRecord>(queueCapacity)
    private val writeLock = Any()
    private var writer: Thread? = null
    private var segment: File? = null
    private var segmentWriter: Writer? = null
    /** Bytes written to [segment] (its file length lags behind the buffered writer). */
    private var segmentBytes = 0L
    /** Highest segment index ever used; indices never repeat, so file order is time order. */
    private var lastSegmentIndex = 0
    /** Per segment: the newest entry id recorded in it (all older ones are older). */
    private val segmentNewest = LinkedHashMap<File, String>()
    private val diskBytes = AtomicLong()

    init {
        if (persisted) load() else newJournal("storage-new")
        if (startWriter && persisted) {
            writer = thread(isDaemon = true, name = "dta-network-store") { writerLoop() }
        }
    }

    fun nextId(): String = "$runId:${seq.incrementAndGet()}"

    fun upsert(entry: NetworkEntry): EntryChange {
        val change = synchronized(lock) {
            if (isEvicted(entry.id)) return EntryChange.UPDATED
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
        enqueue(EntryRecord(entry))
        return change
    }

    /** The entry by id: from memory, else (when it left the recent window) from the journal. */
    fun get(id: String): NetworkEntry? {
        synchronized(lock) {
            entries[id]?.let { return it }
            if (!persisted || isEvicted(id)) return null
        }
        return fromJournal(id)
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
        )
    }

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
        for (file in segmentFiles()) {
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
        return JournalPage(journalId, records, next, end)
    }

    /** Null when bodies cannot be stored (memory-only history). */
    fun openBodySink(id: String, part: BodyPart): BodySink? {
        if (!persisted) return null
        val file = bodyFile(id, part) ?: return null
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
                    diskBytes.addAndGet(written)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun openBody(id: String, part: BodyPart): InputStream? {
        val file = bodyFile(id, part)?.takeIf { it.isFile } ?: return null
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
    fun bodySize(id: String, part: BodyPart): Long? = bodyFile(id, part)?.takeIf { it.isFile }?.length()

    /** Bytes the history occupies on disk: segments plus bodies. */
    fun diskBytes(): Long = diskBytes.get()

    /**
     * Deletes every run's entries, segments and body files; the live run keeps capturing, in
     * a new journal (identity reason `cleared`).
     * @return how many entries were removed.
     */
    fun clear(): Int {
        val removed = synchronized(lock) {
            val count = stored
            entries.clear()
            finishedInMemory = 0
            stored = 0
            earliestMs = null
            runLastTime.clear()
            state = StoreState(clearedAtMs = clock(), journalId = UUID.randomUUID().toString())
            journalReason = "cleared"
            count
        }
        synchronized(writeLock) {
            queue.clear()
            runCatching { segmentWriter?.close() }
            segmentWriter = null
            segment = null
            segmentNewest.clear()
            storage?.segments?.listFiles()?.forEach { it.delete() }
            storage?.bodies?.listFiles()?.forEach { it.delete() }
            diskBytes.set(0)
            saveState()
        }
        return removed
    }

    /** Writes everything queued so far (the writer thread does this continuously). */
    fun flush() = synchronized(writeLock) {
        while (true) write(queue.poll() ?: break)
        runCatching { segmentWriter?.flush() }
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
                    bodyFile(oldest.id, part)?.takeIf { it.isFile }?.let { file ->
                        val size = file.length()
                        if (file.delete()) diskBytes.addAndGet(-size)
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
        if (!persisted || finishedInMemory <= recentWindow) return
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

    private fun load() {
        val dir = storage ?: return
        try {
            val stateFile = stateFile()?.takeIf { it.isFile }
            state = stateFile?.let { DtaJson.decodeFromString(StoreState.serializer(), it.readText()) } ?: StoreState()
            if (stateFile == null || state.journalId == null) newJournal("storage-new")
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
                segmentNewest[file] = newest ?: continue
            }
            dir.bodies.listFiles()?.forEach { diskBytes.addAndGet(it.length()) }
            // The process that owned them is gone (FR-027): the journal says so for the host too.
            pending.values.forEach { enqueue(EntryRecord(endedByProcessDeath(it))) }
        } catch (_: Throwable) {
            // A history that cannot be read is not a reason to stop capturing.
            if (state.journalId == null) newJournal("storage-new")
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
        if (!queue.offer(record)) synchronized(lock) { dropped++ }
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
                    if (record != null) write(record)
                    if (queue.isEmpty()) runCatching { segmentWriter?.flush() }
                }
            } catch (_: InterruptedException) {
                return
            }
            if (queue.isEmpty()) runCatching { evictIfNeeded() }
        }
    }

    private fun write(record: EntryRecord) {
        if (!persisted) return
        if (synchronized(lock) { isEvicted(record.entry.id) }) return
        try {
            val line = DtaJson.encodeToString(EntryRecord.serializer(), record) + "\n"
            val bytes = line.toByteArray(Charsets.UTF_8).size.toLong()
            writerFor(bytes).write(line)
            segmentBytes += bytes
            diskBytes.addAndGet(bytes)
            segment?.let { segmentNewest[it] = newer(segmentNewest[it], record.entry.id) }
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
        val file = File(storage!!.segments, "seg-%06d.jsonl".format(++lastSegmentIndex))
        segment = file
        segmentBytes = 0
        return file.bufferedWriter().also { segmentWriter = it }
    }

    private fun saveState() {
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

    private fun stateFile(): File? = storage?.let { File(it.root, "state.json") }

    private fun segmentIndex(file: File): Int? = SEGMENT.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        val RUN_ID = Regex("""r(\d+)-(\d+)""")
        val SEGMENT = Regex("""seg-(\d+)\.jsonl""")
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
