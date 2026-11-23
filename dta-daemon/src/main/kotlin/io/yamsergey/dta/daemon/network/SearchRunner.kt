package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.FindEnd
import io.yamsergey.dta.protocol.network.FoundItem
import io.yamsergey.dta.protocol.network.PatternStop
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feature 071 (research R6): searches run here, apart from the work that answers requests and applies
 * the app's feed — on [walks] threads of their own; one beyond that waits (FIFO) and is told so. No
 * search is dropped and none is cut by time; each ends when its scope is read or it is stopped.
 */
class SearchRunner(val walks: Int = WALKS) : AutoCloseable {
    private val made = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(walks) { r -> Thread(r, "dta-search-${made.incrementAndGet()}").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val admission = Semaphore(walks)
    private val waitingNow = AtomicInteger()

    /** Searches waiting for a place now. */
    val waiting: Int get() = waitingNow.get()

    /**
     * Runs the blocking [walk] on the search pool once a place is free; [waitingAt] hears the position
     * (searches ahead plus this one) when it has to wait. Cancelling the caller stops the walk through
     * the [PatternStop] it is given, also inside a match. [admitted] runs once it has its place, before
     * the walk (contracts/find.md: `waiting` first when over the limit, then `searching`).
     */
    suspend fun <T> run(waitingAt: suspend (Int) -> Unit = {}, admitted: suspend () -> Unit = {}, walk: (PatternStop) -> T): T {
        if (!admission.tryAcquire()) {
            val position = waitingNow.incrementAndGet()
            try {
                waitingAt(position)
                admission.acquire()
            } finally {
                waitingNow.decrementAndGet()
            }
        }
        try {
            admitted()
            val job = currentCoroutineContext().job
            return withContext(dispatcher) { walk(PatternStop { !job.isActive }) }
        } finally {
            admission.release()
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** Two walks at once: each holds a read of the archive while it runs (research R6). */
        const val WALKS = 2
    }
}

/**
 * Feature 071 (research R7): what a viewer has asked for and not been given yet. A walk gives rows only
 * against credit; without it, it waits (a slow viewer slows the walk, FR-010).
 */
class Credit(initial: Int) {
    private val left = MutableStateFlow(initial.toLong())

    fun more(n: Int) { if (n > 0) left.update { it + n } }

    /** Up to [n] rows, waiting until at least one may go. */
    suspend fun take(n: Int): Int {
        while (true) {
            left.first { it > 0 }
            var got = 0
            left.update { have -> got = minOf(have, n.toLong()).toInt(); have - got }
            if (got > 0) return got
        }
    }

    /** Rows given back (a batch smaller than taken). */
    fun giveBack(n: Int) = more(n)
}

/**
 * Feature 071: one streamed search, from the walk to the viewer. The walk (blocking, on the search pool)
 * hands rows to a small buffer ([BUFFER]); batches of at most [BATCH] rows, or what came in [BATCH_MS],
 * go to [send] against [credit]. Memory held for undelivered rows is the buffer plus one batch.
 */
object FindPipeline {
    const val BUFFER = 64
    const val BATCH = 64
    const val BATCH_MS = 50L

    /**
     * Runs [walk] through [runner] and delivers its rows; returns how it ended. Cancelling the caller
     * stops the walk and nothing more is sent.
     */
    suspend fun run(
        runner: SearchRunner,
        credit: Credit,
        waitingAt: suspend (Int) -> Unit,
        walk: (stop: PatternStop, onItem: (FoundItem) -> Unit) -> FindEnd,
        admitted: suspend () -> Unit = {},
        send: suspend (List<FoundItem>) -> Unit,
    ): FindEnd = coroutineScope {
        val rows = Channel<FoundItem>(BUFFER)
        val walking = async {
            try {
                runner.run(waitingAt, admitted) { stop ->
                    walk(stop) { item ->
                        // Blocks while the buffer is full: the walk goes no further ahead of the viewer.
                        val sent = rows.trySendBlocking(item)
                        if (sent.isClosed) throw io.yamsergey.dta.protocol.network.SearchStoppedException()
                    }
                }
            } finally {
                rows.close()
            }
        }
        try {
            while (true) {
                val first = rows.receiveCatching().getOrNull() ?: break
                val batch = ArrayList<FoundItem>(BATCH).apply { add(first) }
                val until = System.currentTimeMillis() + BATCH_MS
                // Drained without a timed receive: a receive cut by its timeout may lose the row it took.
                while (batch.size < BATCH) {
                    val r = rows.tryReceive()
                    if (r.isSuccess) { batch += r.getOrThrow(); continue }
                    if (r.isClosed) break
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    kotlinx.coroutines.delay(minOf(left, 5))
                }
                var at = 0
                while (at < batch.size) {
                    val n = credit.take(batch.size - at)
                    send(batch.subList(at, at + n).toList())
                    at += n
                }
            }
            walking.await()
        } catch (e: Throwable) {
            rows.cancel()
            walking.cancel()
            throw e
        }
    }
}
