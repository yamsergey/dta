package io.yamsergey.dta.studio.settings

import com.intellij.openapi.diagnostic.logger
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.context.ContextState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Feature 056: the connected app is told the Timeline switch, so it records (or not) from its next start.
 * D-035: always off the EDT, whatever dispatcher the caller's scope has: Settings' apply only flips
 * [switch], and a slow daemon never holds the UI.
 */
internal fun CoroutineScope.forwardTimelineSwitch(
    state: Flow<ContextState>,
    switch: Flow<Boolean>,
    send: suspend (device: String, app: String, on: Boolean) -> Unit,
): Job = launch(Dispatchers.Default) {
    combine(state, switch) { st, on -> Triple(st.device, st.app.takeIf { st.connection == Connection.CONNECTED }, on) }
        .distinctUntilChanged()
        .collect { (device, app, on) ->
            if (device == null || app == null) return@collect
            runCatching { send(device, app, on) }
                .onFailure { logger<TimelineSwitchLog>().info("timeline switch not sent to $app: ${it.message}") }
        }
}

private class TimelineSwitchLog
