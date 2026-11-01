package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.UiEvent

/**
 * The sidekick's journal of UI events (feature 015, research R8): a ring of [capacity] events
 * numbered by seq within this process ([epoch]). Every event is pushed to the connected
 * sessions as it is appended; a reader that comes back reads after its last seq and is told
 * how many events the ring lost meanwhile (constitution III).
 */
class UiJournalBuffer(
    private val capacity: Int = 500,
    val epoch: Long,
    private val publish: (UiEvent) -> Unit,
) {
    data class Read(val epoch: Long, val events: List<UiEvent>, val dropped: Int, val lastSeq: Long)

    private val ring = ArrayDeque<UiEvent>()
    private var seq = 0L

    /** Appends the event [make] builds with its id and seq, then pushes it. */
    fun append(make: (id: String, seq: Long) -> UiEvent): UiEvent {
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
}
