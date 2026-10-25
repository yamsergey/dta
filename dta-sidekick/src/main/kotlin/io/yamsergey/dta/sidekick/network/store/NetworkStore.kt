package io.yamsergey.dta.sidekick.network.store

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.ProcessRun
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.sidekick.network.BodySink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.Writer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** One line of the segment log. The latest entry record per id wins on load. */
@Serializable
internal sealed interface StoreRecord {
    @Serializable
    @SerialName("entry")
    data class EntryRecord(val entry: NetworkEntry) : StoreRecord

    @Serializable
    @SerialName("run")
    data class RunRecord(val run: ProcessRun) : StoreRecord
}

/** Storage bounds (data-model §Bounds); stated in the capability and coverage. */
data class StoreBounds(
    val historyBytes: Long = 100L * 1024 * 1024,
    val bodyBytes: Long = 32L * 1024 * 1024,
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
 * Network history: an in-memory index (insertion order = chronological order, stable
 * under device clock changes) backed by an append-only JSON-lines segment log and body
 * files, written by one background thread so capture never waits on disk metadata.
 *
 * Every failure degrades instead of throwing: an unusable directory or a write error
 * turns persistence off (`coverage.persisted = false`); a full writer queue counts
 * the record as dropped (constitution I, III).
 */
class NetworkStore(
    storage: StorageDir?,
    pid: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = 10_000,
    startWriter: Boolean = true,
    val bounds: StoreBounds = StoreBounds(),
) {
    private val storage: StorageDir? = storage?.takeIf { it.prepare() }

    val startedAtMs: Long = clock()
    val runId: String = "r$startedAtMs-$pid"
    private val seq = AtomicLong()

    private val lock = Any()
    private val entries = LinkedHashMap<String, NetworkEntry>()
    private val runs = mutableListOf(ProcessRun(runId, pid, startedAtMs))
    private var dropped = 0

    @Volatile
    private var persisted = this.storage != null

    private val queue = ArrayBlockingQueue<StoreRecord>(queueCapacity)
    private val writeLock = Any()
    private var writer: Thread? = null
    private var segment: File? = null
    private var segmentWriter: Writer? = null

    init {
        enqueue(StoreRecord.RunRecord(runs.first()))
        if (startWriter && persisted) {
            writer = thread(isDaemon = true, name = "dta-network-store") { writerLoop() }
        }
    }

    fun nextId(): String = "$runId:${seq.incrementAndGet()}"

    fun upsert(entry: NetworkEntry): EntryChange {
        val change = synchronized(lock) {
            if (entries.put(entry.id, entry) == null) EntryChange.CREATED else EntryChange.UPDATED
        }
        enqueue(StoreRecord.EntryRecord(entry))
        return change
    }

    fun get(id: String): NetworkEntry? = synchronized(lock) { entries[id] }

    /** Every stored entry in chronological order. */
    fun snapshot(): List<NetworkEntry> = synchronized(lock) { entries.values.toList() }

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
            searched = entries.size,
            stored = entries.size,
            earliestMs = entries.values.firstOrNull()?.timeMs,
            dropped = dropped,
            persisted = persisted,
            runs = runs.toList(),
        )
    }

    /** Null when bodies cannot be stored (memory-only history). */
    fun openBodySink(id: String, part: BodyPart): BodySink? {
        if (!persisted) return null
        val file = bodyFile(id, part) ?: return null
        return try {
            val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
            object : BodySink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
                override fun close() = out.close()
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

    /** Writes everything queued so far (the writer thread does this continuously). */
    fun flush() = synchronized(writeLock) {
        while (true) write(queue.poll() ?: break)
        segmentWriter?.flush()
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

    private fun bodyFile(id: String, part: BodyPart): File? =
        storage?.let { File(it.bodies, "${id.replace(':', '_')}.${part.name.lowercase()}") }

    private fun enqueue(record: StoreRecord) {
        if (!persisted) return
        if (!queue.offer(record)) synchronized(lock) { dropped++ }
    }

    private fun writerLoop() {
        while (persisted) {
            val record = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            synchronized(writeLock) {
                write(record)
                if (queue.isEmpty()) runCatching { segmentWriter?.flush() }
            }
        }
    }

    private fun write(record: StoreRecord) {
        if (!persisted) return
        try {
            val line = DtaJson.encodeToString(StoreRecord.serializer(), record) + "\n"
            writerFor(line.length).write(line)
        } catch (_: Throwable) {
            persisted = false
            runCatching { segmentWriter?.close() }
            segmentWriter = null
        }
    }

    private fun writerFor(incoming: Int): Writer {
        val current = segment
        val writer = segmentWriter
        if (current != null && writer != null && current.length() + incoming <= bounds.segmentBytes) return writer
        writer?.flush()
        writer?.close()
        val dir = storage!!.segments
        val nextIndex = (dir.listFiles()?.mapNotNull { segmentIndex(it) }?.maxOrNull() ?: 0) + 1
        val file = File(dir, "seg-%06d.jsonl".format(nextIndex))
        segment = file
        return file.bufferedWriter().also { segmentWriter = it }
    }

    private fun segmentIndex(file: File): Int? =
        Regex("""seg-(\d+)\.jsonl""").matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
}
