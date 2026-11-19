package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.FeedBatch
import io.yamsergey.dta.protocol.network.FeedEnded
import io.yamsergey.dta.protocol.network.FeedOpenParams
import io.yamsergey.dta.protocol.network.FeedOpenResult
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Feature 068 (research R1, R4; contracts/feed.md): the app's journal sent to this computer as one
 * feed. Opened from the position the computer read to, it sends what was written after it, then
 * each record once it is on disk, in [FeedBatch]es that each start where the previous ended.
 *
 * The writer thread only offers to a bounded queue and never waits (constitution I); one sender
 * thread per subscription sends. A subscriber that falls [maxQueued] records behind is ended at
 * the last position sent ([FeedEnded.OVERFLOW]) and opens again from there.
 */
class NetworkFeed(
    private val store: NetworkStore,
    private val send: (String, JsonElement?) -> Unit,
    private val maxQueued: Int = 20_000,
) {
    private val ids = AtomicInteger()

    @Volatile
    private var current: Sub? = null

    /** The subscription in force, if any. */
    fun current(): String? = current?.id

    @Synchronized
    fun open(params: FeedOpenParams): FeedOpenResult {
        val identity = store.identity()
        if (!identity.persisted) return FeedOpenResult(refused = FeedOpenResult.NOT_PERSISTED)
        if (store.isReading) return FeedOpenResult(refused = FeedOpenResult.READING)
        if (identity.journalId != params.journalId) return FeedOpenResult(refused = FeedOpenResult.JOURNAL_CHANGED)
        current?.stop()
        current = null
        val sub = Sub("f${ids.incrementAndGet()}", identity.journalId, params.after)
        val start = store.startLive(params.after, PAGE, onEnd = { sub.end(FeedEnded.CLOSED) }) { entry, at -> sub.offer(entry, at) }
            ?: return FeedOpenResult(refused = if (store.isReading) FeedOpenResult.READING else FeedOpenResult.BEHIND)
        sub.begin(start)
        current = sub
        return FeedOpenResult(subscription = sub.id)
    }

    /** The daemon stops [subscription]; nothing more is sent for it. */
    @Synchronized
    fun close(subscription: String) {
        val sub = current?.takeIf { it.id == subscription } ?: return
        sub.stop()
        store.stopLive()
        current = null
    }

    @Synchronized
    fun close() {
        current?.let { close(it.id) }
    }

    private inner class Sub(val id: String, val journalId: String, private var sentTo: String) {
        private val queue = ArrayBlockingQueue<Pair<NetworkEntry, String>>(maxQueued)

        @Volatile
        private var running = true

        @Volatile
        private var ended: String? = null
        private var first: NetworkStore.LiveStart? = null

        /** On the writer thread: never waits. */
        fun offer(entry: NetworkEntry, at: String) {
            if (!running || ended != null) return
            if (!queue.offer(entry to at)) {
                ended = FeedEnded.OVERFLOW
                store.stopLive()
            }
        }

        fun end(reason: String) {
            if (ended == null) ended = reason
        }

        fun stop() {
            running = false
        }

        fun begin(start: NetworkStore.LiveStart) {
            first = start
            thread(isDaemon = true, name = "dta-network-feed") { run() }
        }

        private fun run() {
            try {
                // What was written after the position, even none: it says where the live records start.
                first?.let { batch(it.records, it.to) }
                first = null
                val chunk = ArrayList<Pair<NetworkEntry, String>>(BATCH)
                while (running) {
                    val next = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (next == null) {
                        if (ended != null) break
                        continue
                    }
                    chunk += next
                    queue.drainTo(chunk, BATCH - 1)
                    batch(chunk.map { it.first }, chunk.last().second)
                    chunk.clear()
                }
                val reason = ended
                if (running && reason != null) {
                    send(FeedEnded.EVENT, DtaJson.encodeToJsonElement(FeedEnded.serializer(), FeedEnded(id, reason, sentTo)))
                }
            } catch (_: InterruptedException) {
                // Stopped.
            } catch (_: Throwable) {
                // The session is gone: the daemon opens again when it is back.
            } finally {
                running = false
                synchronized(this@NetworkFeed) { if (current === this) current = null }
            }
        }

        private fun batch(records: List<NetworkEntry>, to: String) {
            if (!running) return
            send(FeedBatch.EVENT, DtaJson.encodeToJsonElement(FeedBatch.serializer(), FeedBatch(id, journalId, sentTo, to, records)))
            sentTo = to
        }
    }

    companion object {
        /** At most this many records follow the position a subscription opens from; more: read pages first. */
        const val PAGE = 2_000
        /** Records per live batch. */
        const val BATCH = 500
    }
}
