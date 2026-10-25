package io.yamsergey.dta.sidekick.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Broadcasts events to every connected session (network.entry is for all clients,
 * not just the one that asked). [publish] never blocks the caller — capture runs on
 * app threads: each session has a bounded outbox drained by its own sender thread;
 * overflow is counted and reported to that session once as `network.events-dropped`
 * when it catches up (constitution I, III). Rides the existing channel (II).
 */
class SessionHub(private val outboxCapacity: Int = 1000) {

    /** A session events can be pushed to. */
    interface Sink {
        /** Events are only delivered after the session's hello. */
        val helloCompleted: Boolean
        fun send(name: String, data: JsonElement?)
    }

    /** Returned by [add]; closing it detaches the session. */
    inner class Handle internal constructor(internal val sink: Sink) : AutoCloseable {
        private val outbox = ArrayBlockingQueue<Pair<String, JsonElement?>>(outboxCapacity)
        private val dropped = AtomicInteger()

        @Volatile
        private var open = true

        private val sender = thread(isDaemon = true, name = "dta-session-events") { drain() }

        internal fun offer(name: String, data: JsonElement?) {
            if (!open || !sink.helloCompleted) return
            if (!outbox.offer(name to data)) dropped.incrementAndGet()
        }

        private fun drain() {
            while (open) {
                val (name, data) = try {
                    outbox.take()
                } catch (_: InterruptedException) {
                    return
                }
                try {
                    val lost = dropped.getAndSet(0)
                    if (lost > 0) sink.send(EVENTS_DROPPED, buildJsonObject { put("count", lost) })
                    sink.send(name, data)
                } catch (_: Throwable) {
                    close()
                }
            }
        }

        override fun close() {
            open = false
            handles.remove(this)
            sender.interrupt()
        }
    }

    private val handles = CopyOnWriteArrayList<Handle>()

    val sessionCount: Int get() = handles.size

    fun add(sink: Sink): Handle = Handle(sink).also { handles += it }

    fun publish(name: String, data: JsonElement?) {
        for (handle in handles) handle.offer(name, data)
    }

    fun close() {
        for (handle in handles) handle.close()
    }

    companion object {
        const val EVENTS_DROPPED = "network.events-dropped"
    }
}
