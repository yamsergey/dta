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
    private var dropReason: String? = if (sink == null) "body storage unavailable" else null
    private var finished = false

    @Synchronized
    fun accept(buffer: ByteArray, offset: Int, length: Int) {
        if (finished || length <= 0) return
        observed += length
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
                if (observed == 0L) "the app read none of the body" else "app stopped reading after $observed bytes",
            )
            else -> BodyCapture(stored, observed, Completeness.COMPLETE, null)
        }
        deliver(capture)
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

/** Response side: tees what the app reads. End of stream completes; an early close or a read error is partial. */
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
        try {
            super.close()
        } finally {
            recorder.finish(endOfStream = false)
        }
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
