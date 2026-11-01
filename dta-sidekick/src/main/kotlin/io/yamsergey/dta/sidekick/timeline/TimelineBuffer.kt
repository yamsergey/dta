package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.TimelineEvent

/**
 * The sidekick's timeline (features 015/016, research R1): a ring of [capacity] events from
 * every source, numbered by seq within this process ([epoch]). Every event is pushed to the
 * connected sessions as it is appended; a reader that comes back reads after its last seq and
 * is told how many events the ring lost meanwhile (constitution III).
 */
class TimelineBuffer(
    private val capacity: Int = CAPACITY,
    val epoch: Long,
    private val publish: (TimelineEvent) -> Unit,
) {
    data class Read(val epoch: Long, val events: List<TimelineEvent>, val dropped: Int, val lastSeq: Long)

    private val ring = ArrayDeque<TimelineEvent>()
    private var seq = 0L

    /** Appends the event [make] builds with its id and seq, then pushes it. */
    fun append(make: (id: String, seq: Long) -> TimelineEvent): TimelineEvent {
        val event = synchronized(this) {
            seq++
            make("$epoch:$seq", seq).also {
                ring.addLast(it)
                if (ring.size > capacity) ring.removeFirst()
            }
        }
        publish(event)
        return event
    }

    @Synchronized
    fun read(afterSeq: Long?, limit: Int): Read {
        val after = afterSeq ?: 0L
        val oldest = ring.firstOrNull()?.seq ?: (seq + 1)
        val dropped = (oldest - after - 1).coerceAtLeast(0).toInt()
        val events = ring.asSequence().filter { it.seq > after }.take(limit).toList()
        return Read(epoch, events, dropped, seq)
    }

    @get:Synchronized
    val lastSeq: Long get() = seq

    companion object {
        const val CAPACITY = 1_000
    }
}
