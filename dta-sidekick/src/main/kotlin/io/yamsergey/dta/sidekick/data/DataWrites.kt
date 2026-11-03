package io.yamsergey.dta.sidekick.data

import io.yamsergey.dta.protocol.timeline.AppPayload
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.TimelineRuntime

/** Feature 027 US7: every write DTA makes is an event of the timeline's `dta` source. */
internal object DataWrites {
    const val KIND = "data/write"

    fun record(store: String, change: String, op: String): String? = runCatching {
        TimelineRuntime.buffer?.append { id, seq ->
            TimelineEvent(
                id = id, seq = seq,
                monoMs = android.os.SystemClock.uptimeMillis(), wallMs = System.currentTimeMillis(),
                source = Sources.DTA, kind = KIND, title = "$store: $change",
                app = AppPayload("data.$op", mapOf("store" to store, "change" to change)),
            )
        }?.id
    }.getOrNull()
}
