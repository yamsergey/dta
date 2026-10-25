package io.yamsergey.dta.sidekick.network.script

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Delivers [bytesPerSecond] at most (`throttle()`, feature 005 US4): reads are capped to a
 * small slice and paced against the elapsed time; the bytes are never altered.
 */
class ThrottleInputStream(
    input: InputStream,
    private val bytesPerSecond: Long,
    private val nanoClock: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : FilterInputStream(input) {

    private val start = nanoClock()
    private var delivered = 0L

    @Volatile
    private var closed = false

    /** At most ~1/10 s worth per read, so pacing stays smooth. */
    private val slice = (bytesPerSecond / 10).coerceIn(1, 64 * 1024).toInt()

    override fun read(): Int {
        pace(1)
        val b = super.read()
        if (b >= 0) delivered++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val want = minOf(len, slice)
        pace(want)
        val n = `in`.read(b, off, want)
        if (n > 0) delivered += n
        return n
    }

    override fun close() {
        closed = true
        super.close()
    }

    /** Waits until delivering [next] more bytes keeps within the rate. */
    private fun pace(next: Int) {
        if (closed) throw IOException("stream closed")
        val dueNanos = (delivered + next) * 1_000_000_000L / bytesPerSecond
        val waitMs = (dueNanos - (nanoClock() - start)) / 1_000_000
        if (waitMs > 0) sleep(waitMs)
    }
}
