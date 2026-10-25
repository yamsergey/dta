package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.ChangedField
import io.yamsergey.dta.protocol.network.InterventionRecord
import io.yamsergey.dta.protocol.network.ScriptHandler

/** Script results → the [InterventionRecord] on a captured entry (FR-016). Pure. */
object InterventionDiff {

    /** Null when the handler did nothing visible (no effect, delay, throttle or error). */
    fun record(scriptId: String, handler: ScriptHandler, result: ScriptResult<*>): InterventionRecord? {
        val effect = result.effect
        val acted = effect != Effect.None || result.delayMs > 0 || result.throttleBytesPerSecond != null
        if (!acted && result.error == null) return null
        return InterventionRecord(
            scriptId = scriptId,
            handlers = if (acted) listOf(handler) else emptyList(),
            changed = (effect as? Effect.Change<*>)?.let { changedFields(it.change) } ?: emptyList(),
            delayMs = result.delayMs.takeIf { it > 0 },
            scriptMs = result.scriptMs,
            throttleBytesPerSecond = result.throttleBytesPerSecond,
            mocked = effect is Effect.Mock,
            dropped = effect == Effect.Drop,
            error = result.error,
        )
    }

    fun merge(a: InterventionRecord?, b: InterventionRecord?): InterventionRecord? {
        if (a == null) return b
        if (b == null) return a
        return InterventionRecord(
            scriptId = b.scriptId,
            handlers = (a.handlers + b.handlers).distinct(),
            changed = (a.changed + b.changed).distinct(),
            delayMs = if (a.delayMs == null && b.delayMs == null) null else (a.delayMs ?: 0) + (b.delayMs ?: 0),
            scriptMs = a.scriptMs + b.scriptMs,
            throttleBytesPerSecond = b.throttleBytesPerSecond ?: a.throttleBytesPerSecond,
            mocked = a.mocked || b.mocked,
            dropped = a.dropped || b.dropped,
            error = listOfNotNull(a.error, b.error).joinToString("; ").ifEmpty { null },
        )
    }

    private fun changedFields(change: Any?): List<ChangedField> = when (change) {
        is RequestChange -> listOfNotNull(
            change.url?.let { ChangedField.URL },
            change.method?.let { ChangedField.METHOD },
            change.headers?.let { ChangedField.HEADERS },
            change.body?.let { ChangedField.BODY },
        )
        is ResponseChange -> listOfNotNull(
            change.status?.let { ChangedField.STATUS },
            change.headers?.let { ChangedField.HEADERS },
            change.body?.let { ChangedField.BODY },
        )
        is MessageChange -> listOf(ChangedField.PAYLOAD)
        else -> emptyList()
    }
}
