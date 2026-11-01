package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.timeline.TimelineCosts
import io.yamsergey.dta.protocol.timeline.TimelineRead
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The host's side of the timeline (contract timeline-and-state §sidekick methods). */
object TimelineMethods {
    private const val MAX_LIMIT = 500

    /** Costs of the sources, gathered when read (summaries, scopes, bridge calls). */
    @Volatile
    var costs: () -> TimelineCosts? = { null }

    fun install(registry: MethodRegistry) {
        registry.register("timeline_read") { ctx -> DtaJson.encodeToJsonElement(TimelineRead.serializer(), read(ctx.params)) }
        registry.register("timeline_status") { DtaJson.encodeToJsonElement(TimelineRead.serializer(), read(null, statusOnly = true)) }
    }

    fun read(params: JsonObject?, statusOnly: Boolean = false): TimelineRead {
        val sources = TimelineRuntime.sources()
        val buffer = TimelineRuntime.buffer ?: return TimelineRead(epoch = 0, events = emptyList(), sources = sources)
        val after = params?.get("afterSeq")?.jsonPrimitive?.long
        val limit = (params?.get("limit")?.jsonPrimitive?.int ?: 100).coerceIn(1, MAX_LIMIT)
        val read = buffer.read(after, if (statusOnly) 0 else limit)
        return TimelineRead(
            read.epoch, read.events, read.dropped, read.lastSeq, sources,
            runCatching { costs() }.getOrNull(), TimelineBuffer.CAPACITY,
            facets = TimelineRuntime.facets.snapshot(),
        )
    }
}
