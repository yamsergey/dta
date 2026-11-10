package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.TimelineSwitch
import io.yamsergey.dta.protocol.ui.SourceState
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Feature 056: whether this app records the timeline, kept as a marker file in the sidekick's own
 * no-backup storage (one `exists` at start). Off by default; set by the IDE or an agent.
 */
class TimelineSwitchStore(private val dir: File) {
    private val marker get() = File(dir, "timeline-on")

    val on: Boolean get() = runCatching { marker.exists() }.getOrDefault(false)

    fun set(on: Boolean) {
        if (on) { dir.mkdirs(); marker.createNewFile() } else marker.delete()
    }
}

/** The app method `timeline_enabled`: read, or set and say what it means for this process. */
class TimelineSwitchMethods(
    private val store: TimelineSwitchStore,
    private val running: () -> Boolean,
    private val start: () -> Unit,
) {
    fun install(registry: MethodRegistry) {
        registry.register("timeline_enabled") { ctx ->
            val on = ctx.params?.get("on")?.jsonPrimitive?.booleanOrNull
            DtaJson.encodeToJsonElement(TimelineSwitch.serializer(), answer(on))
        }
    }

    fun answer(on: Boolean?): TimelineSwitch {
        when (on) {
            true -> {
                store.set(true)
                if (!running()) runCatching(start)
                return TimelineSwitch(true, running(), if (running()) "recording from now; from the app's start on its next launch" else "on from the app's next start")
            }
            false -> {
                store.set(false)
                return TimelineSwitch(false, running(), if (running()) "recording stops at the app's next start" else null)
            }
            null -> return TimelineSwitch(store.on, running())
        }
    }

    companion object {
        const val SOURCE = "timeline"

        /** The status a timeline read shows while the switch is off. */
        fun offStatus() = SourceStatus(SOURCE, SourceState.OFF, TimelineSwitch.OFF_REASON)
    }
}
