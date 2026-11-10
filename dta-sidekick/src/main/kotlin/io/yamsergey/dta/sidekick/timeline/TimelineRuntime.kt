package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import kotlinx.serialization.json.JsonElement

/**
 * Process-wide timeline (feature 016): the ring every source appends to, the facets they own,
 * and each source's status. Created by the first source that starts; never throws into the app.
 */
object TimelineRuntime {
    const val EVENT = "timeline.event"

    @Volatile
    var buffer: TimelineBuffer? = null
        private set

    val facets = FacetState()

    /** The cause from the handling scope open on this thread (016 US7); none until input is recorded. */
    @Volatile
    var scopeCause: () -> io.yamsergey.dta.protocol.timeline.CauseLink? = { null }

    private val statuses = LinkedHashMap<String, () -> SourceStatus>()

    @Synchronized
    fun start(publish: (String, JsonElement?) -> Unit): TimelineBuffer =
        buffer ?: TimelineBuffer(epoch = System.currentTimeMillis()) { event ->
            runCatching { publish(EVENT, DtaJson.encodeToJsonElement(TimelineEvent.serializer(), event)) }
        }.also { buffer = it }

    /** A source states how it is (on, off with the reason, degraded with the reason). */
    @Synchronized
    fun register(name: String, status: () -> SourceStatus) {
        statuses[name] = status
    }

    @Synchronized
    fun unregister(name: String) {
        statuses.remove(name)
    }

    @Synchronized
    fun sources(): List<SourceStatus> = statuses.values.mapNotNull { runCatching(it).getOrNull() }
}
