package io.yamsergey.dta.sidekick.data

import io.yamsergey.dta.protocol.timeline.AppPayload
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.TimelineRuntime

/** Feature 027 US7 / 028: every write DTA makes is an event of the timeline's `dta` source. */
internal object DataWrites {
    const val KIND = "data/write"

    /** Feature 028: writes into the running app (navigation, ViewModels, functions). */
    const val RUNTIME_KIND = "runtime/write"

    fun record(store: String, change: String, op: String, kind: String = KIND): String? = runCatching {
        TimelineRuntime.buffer?.append { id, seq ->
            TimelineEvent(
                id = id, seq = seq,
                monoMs = android.os.SystemClock.uptimeMillis(), wallMs = System.currentTimeMillis(),
                source = Sources.DTA, kind = kind, title = "$store: $change",
                app = AppPayload(if (kind == KIND) "data.$op" else "runtime.$op", mapOf("target" to store, "change" to change)),
            )
        }?.id
    }.getOrNull()
}
