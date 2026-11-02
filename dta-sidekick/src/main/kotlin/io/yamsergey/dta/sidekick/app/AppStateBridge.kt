package io.yamsergey.dta.sidekick.app

import android.os.SystemClock
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.sidekick.timeline.HandlingScope
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * The app's own source (feature 016 US3, research R8): when the app ships `dta-state`, an
 * ENTRY hook on its empty `DtaStateBridge.emit` reads each call's arguments, and the bridge's
 * `on` flag — our own library's, set only here — lets its calls through. Without the library
 * the source is off and says so; an unknown bridge version is degraded, never guessed at.
 */
class AppStateBridge(private val buffer: TimelineBuffer, private val facets: FacetState, private val scope: HandlingScope) {
    @Volatile
    private var status = SourceStatus(Sources.APP, SourceState.OFF, "not started")

    @Volatile
    var decoder: AppStateDecoder? = null
        private set

    fun install(loader: ClassLoader?) {
        val bridge = try {
            Class.forName(BRIDGE, false, loader)
        } catch (_: Throwable) {
            status = SourceStatus(Sources.APP, SourceState.OFF, "not used by the app (no dta-state library)")
            return
        }
        val version = runCatching { bridge.getField("VERSION").getInt(null) }.getOrNull()
        if (version != VERSION) {
            status = SourceStatus(Sources.APP, SourceState.DEGRADED, "dta-state bridge version $version; this DTA reads version $VERSION")
            return
        }
        val created = AppStateDecoder(buffer, facets, scope, mono = SystemClock::uptimeMillis, wall = System::currentTimeMillis)
        val result = Hooks.entry(bridge, "emit", EMIT, EntryHandler { _, p ->
            @Suppress("UNCHECKED_CAST")
            created.emit(p[0] as Int, p[1] as String?, p[2] as String?, p[3] as Array<String>?, p[4] as Long, p[5] as Long)
        })
        status = when (result) {
            is Hooks.Install.Installed -> {
                runCatching { bridge.getField("on").setBoolean(null, true) }.fold(
                    onSuccess = { SourceStatus(Sources.APP, SourceState.ON, detail = "the app's facets, events and causes through dta-state, at most 200 events a second") },
                    onFailure = { SourceStatus(Sources.APP, SourceState.OFF, "could not switch the dta-state bridge on: ${it.message}") },
                )
            }
            is Hooks.Install.ClassAbsent -> SourceStatus(Sources.APP, SourceState.OFF, "dta-state bridge not found")
            else -> SourceStatus(Sources.APP, SourceState.OFF, "dta-state bridge not hooked: ${result.describe()}")
        }
        decoder = created
    }

    fun status(): SourceStatus = status

    private companion object {
        const val BRIDGE = "io.yamsergey.dta.state.DtaStateBridge"
        const val EMIT = "(ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;JJ)V"
        const val VERSION = 1
    }
}
