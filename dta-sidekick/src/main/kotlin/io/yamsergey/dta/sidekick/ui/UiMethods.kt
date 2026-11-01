package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.ui.UiJournalRead
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The host's side of the UI journal (contract ui-journal §sidekick methods). */
object UiMethods {
    private const val MAX_LIMIT = 500

    fun install(registry: MethodRegistry) {
        registry.register("ui_journal_read") { ctx -> DtaJson.encodeToJsonElement(UiJournalRead.serializer(), read(ctx.params)) }
        registry.register("ui_journal_status") { DtaJson.encodeToJsonElement(UiJournalRead.serializer(), read(null, statusOnly = true)) }
    }

    fun read(params: JsonObject?, statusOnly: Boolean = false): UiJournalRead {
        val buffer = UiRuntime.buffer ?: return UiJournalRead(epoch = 0, events = emptyList(), capability = UiRuntime.journalCapability())
        val after = params?.get("afterSeq")?.jsonPrimitive?.long
        val limit = (params?.get("limit")?.jsonPrimitive?.int ?: 100).coerceIn(1, MAX_LIMIT)
        val read = buffer.read(after, if (statusOnly) 0 else limit)
        return UiJournalRead(read.epoch, read.events, read.dropped, read.lastSeq, UiRuntime.journalCapability(), UiRuntime.costs())
    }
}
