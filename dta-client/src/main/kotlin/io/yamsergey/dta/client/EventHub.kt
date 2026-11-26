package io.yamsergey.dta.client

import io.yamsergey.dta.protocol.rpc.Event
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** D-048: the event a collector gets in place of events it was too far behind to take; `data.count` says how many. */
internal const val DROPPED_EVENT_NAME = "client.events-dropped"

/**
 * Pushed events for any number of collectors. Each collector has its own buffer of [capacity]; the socket
 * reader never waits for one (a slow collector must not stall responses or the other collectors). When a
 * collector's buffer is full the event is not kept for it, but counted, and the collector gets one
 * [DROPPED_EVENT_NAME] event with the count once it has taken what was buffered, so a drop is stated
 * where it happened, like the daemon's `network-events-dropped`.
 */
internal class EventHub(private val capacity: Int = 1024) {
    private class Sub(capacity: Int) {
        val buffer = Channel<Event>(capacity)
        val dropped = AtomicLong()
    }

    private val subs = CopyOnWriteArrayList<Sub>()

    val flow: Flow<Event> = flow {
        val sub = Sub(capacity)
        subs.add(sub)
        try {
            while (true) {
                val next = sub.buffer.tryReceive().getOrNull()
                if (next != null) { emit(next); continue }
                val n = sub.dropped.getAndSet(0)
                if (n > 0) emit(Event(DROPPED_EVENT_NAME, 0, JsonObject(mapOf("count" to JsonPrimitive(n)))))
                else emit(sub.buffer.receive())
            }
        } finally {
            subs.remove(sub)
        }
    }

    fun publish(event: Event) {
        for (sub in subs) if (!sub.buffer.trySend(event).isSuccess) sub.dropped.incrementAndGet()
    }
}
