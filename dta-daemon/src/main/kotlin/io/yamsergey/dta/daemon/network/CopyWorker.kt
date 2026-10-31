package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.ConnectionLostException
import io.yamsergey.dta.daemon.connection.RequestTimeoutException
import kotlinx.coroutines.delay

/**
 * Copies finished entries' bodies to the host as soon as they exist (feature 011 US7, research
 * R6): within a byte budget per second, halved when the channel's round-trips double against
 * their median and recovered by 10% per normal chunk. A lost link ends the run; the next run
 * resumes each body from its last chunk.
 */
class CopyWorker(
    private val index: HostIndex,
    private val bytesPerSecond: Long = DEFAULT_BYTES_PER_SECOND,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The current budget in bytes per second. */
    @Volatile
    var rate: Long = bytesPerSecond
        private set

    private val roundTrips = ArrayDeque<Long>()

    /** Copies what is pending now; returns the bytes copied. */
    suspend fun runOnce(): Long {
        var copied = 0L
        val started = clock()
        for (body in index.pendingBodies()) {
            try {
                index.copyBody(body.entryId, body.part) { bytes, ms ->
                    adapt(ms)
                    copied += bytes
                    val due = copied * 1000 / rate
                    val elapsed = clock() - started
                    if (due > elapsed) sleep(due - elapsed)
                }
            } catch (_: ConnectionLostException) {
                return copied
            } catch (_: RequestTimeoutException) {
                return copied
            }
        }
        return copied
    }

    private fun adapt(ms: Long) {
        val median = roundTrips.sorted().let { if (it.size >= 3) it[it.size / 2] else null }
        rate = if (median != null && ms > 2 * median) maxOf(MIN_BYTES_PER_SECOND, rate / 2) else minOf(bytesPerSecond, rate + rate / 10)
        roundTrips.addLast(ms)
        if (roundTrips.size > WINDOW) roundTrips.removeFirst()
    }

    companion object {
        /** Research R0: well under what the emulator sustained (165 MB/s), room for USB 2 and Wi-Fi adb. */
        const val DEFAULT_BYTES_PER_SECOND = 8L * 1024 * 1024
        const val MIN_BYTES_PER_SECOND = 256L * 1024
        private const val WINDOW = 31
    }
}
