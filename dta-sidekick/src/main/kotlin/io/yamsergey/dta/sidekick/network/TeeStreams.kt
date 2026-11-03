package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.Completeness
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Where a teed body's bytes are stored; provided by the store. */
interface BodySink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

/** Outcome of teeing one body. [reason] is set unless [completeness] is complete. */
data class BodyCapture(
    val storedBytes: Long,
    val observedBytes: Long,
    val completeness: Completeness,
    val reason: String?,
)

/**
 * Copies the bytes an app body stream carries into a [BodySink], up to [bound] bytes,
 * and reports how complete the copy is once. Never throws: storage failure turns into
 * `dropped`, the app's own bytes are never altered (FR-018, constitution I).
 */
class BodyRecorder(
    private var sink: BodySink?,
    /** Bytes stored at most; null stores every byte the app reads. */
    private val bound: Long?,
    /** Content-Length when known: seeing all of it is complete even if the app never reads EOF. */
    private val declaredBytes: Long? = null,
    private val onDone: (BodyCapture) -> Unit,
) {
    private var stored = 0L
    private var observed = 0L
    /** Bytes the app itself read; the rest of [observed] was taken at close from what had arrived. */
    private var appRead = 0L
    private var dropReason: String? = if (sink == null) "body storage unavailable" else null
    private var finished = false

    @Synchronized
    fun accept(buffer: ByteArray, offset: Int, length: Int, byApp: Boolean = true) {
        if (finished || length <= 0) return
        observed += length
        if (byApp) appRead += length
        val target = sink ?: return
        val room = if (bound == null) length else (bound - stored).coerceAtLeast(0).coerceAtMost(length.toLong()).toInt()
        if (room == 0) return
        try {
            target.write(buffer, offset, room)
            stored += room
        } catch (t: Throwable) {
            dropReason = "body storage failed: ${t.message ?: t.javaClass.simpleName}"
            closeSink()
        }
    }

    /** The body exists but cannot be observed without changing the app's behavior. */
    @Synchronized
    fun omit(reason: String) {
        if (finished) return
        finished = true
        closeSink()
        deliver(BodyCapture(0, 0, Completeness.OMITTED, reason))
    }

    @Synchronized
    fun finish(endOfStream: Boolean) {
        if (finished) return
        finished = true
        closeSink()
        val capture = when {
            dropReason != null -> BodyCapture(stored, observed, Completeness.DROPPED, dropReason)
            observed > stored -> BodyCapture(
                stored, observed, Completeness.TRUNCATED,
                "per-body bound of $bound bytes reached; $observed bytes observed",
            )
            !endOfStream && observed != declaredBytes -> BodyCapture(
                stored, observed, Completeness.PARTIALLY_OBSERVED,
                (if (appRead == 0L) "the app closed the body without reading it" else "the app stopped reading after $appRead bytes") +
                    if (declaredBytes != null) "; $observed of $declaredBytes bytes had arrived"
                    else "; $observed bytes had arrived, the end was not seen",
            )
            else -> BodyCapture(stored, observed, Completeness.COMPLETE, null)
        }
        deliver(capture)
    }

    /** How many more bytes are worth taking at close: up to the declared end, or [CLOSE_LIMIT]; 0 once finished. */
    @Synchronized
    fun stillWanted(): Long = when {
        finished -> 0
        declaredBytes != null -> (declaredBytes - observed).coerceIn(0, CLOSE_LIMIT)
        else -> CLOSE_LIMIT
    }

    private fun deliver(capture: BodyCapture) {
        try {
            onDone(capture)
        } catch (_: Throwable) {
            // The handler belongs to the recorder, which contains and reports its own failures.
        }
    }

    private fun closeSink() {
        val target = sink ?: return
        sink = null
        try {
            target.close()
        } catch (_: Throwable) {
        }
    }
}

/** At most this many bytes are taken at close from what had already arrived. */
private const val CLOSE_LIMIT = 1L shl 20

/**
 * Response side: tees what the app reads. End of stream completes; a read error is partial.
 * When the app closes before the end (SDKs and `Unit` calls close unread), the bytes that have
 * already arrived are taken first: only `available()` bytes, so the close never waits for the
 * network, and those bytes are discarded by the close anyway (feature 023).
 */
class TeeInputStream(input: InputStream, private val recorder: BodyRecorder) : FilterInputStream(input) {

    override fun read(): Int {
        val b = try {
            super.read()
        } catch (e: Throwable) {
            recorder.finish(endOfStream = false)
            throw e
        }
        if (b == -1) recorder.finish(endOfStream = true) else recorder.accept(byteArrayOf(b.toByte()), 0, 1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = try {
            `in`.read(b, off, len)
        } catch (e: Throwable) {
            recorder.finish(endOfStream = false)
            throw e
        }
        if (n == -1) recorder.finish(endOfStream = true) else recorder.accept(b, off, n)
        return n
    }

    override fun skip(n: Long): Long {
        // Skipped bytes are not observed; reading them would change nothing for the
        // app but the body would no longer be what the app saw, so it is partial.
        val skipped = super.skip(n)
        if (skipped > 0) recorder.finish(endOfStream = false)
        return skipped
    }

    override fun close() {
        val ended = try {
            takeArrived()
        } catch (_: Throwable) {
            false
        }
        try {
            super.close()
        } finally {
            recorder.finish(endOfStream = ended)
        }
    }

    /** True when the end of the stream was seen. */
    private fun takeArrived(): Boolean {
        // A decompressing stream reports 1 available until its end: reading it could wait.
        if (`in` is java.util.zip.InflaterInputStream) return false
        var wanted = recorder.stillWanted()
        if (wanted <= 0) return false
        val buffer = ByteArray(8192)
        while (wanted > 0) {
            val ready = `in`.available()
            if (ready <= 0) return false
            val n = `in`.read(buffer, 0, minOf(ready.toLong(), wanted, buffer.size.toLong()).toInt())
            if (n == -1) return true
            if (n == 0) return false
            recorder.accept(buffer, 0, n, byApp = false)
            wanted -= n
        }
        return false
    }
}

/** Request side: tees what the app writes; closing completes the body. */
class TeeOutputStream(output: OutputStream, private val recorder: BodyRecorder) : FilterOutputStream(output) {

    override fun write(b: Int) {
        out.write(b)
        recorder.accept(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        recorder.accept(b, off, len)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            recorder.finish(endOfStream = true)
        }
    }
}
