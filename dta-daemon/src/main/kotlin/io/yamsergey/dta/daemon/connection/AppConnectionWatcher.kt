package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the apps with DTA on a device connected while someone watches them (feature 009,
 * FR-017): an app that starts is connected (or refused), one whose process went away is
 * ended, a link that died while the app runs is lost and re-dialled. The daemon does the
 * looking so its clients never poll. Events come from the [manager], the only owner of
 * connection state.
 */
class AppConnectionWatcher(
    /** Running apps with a sidekick on [serial], or on every device when null. */
    private val instances: (serial: String?) -> List<ConnectionKey>,
    private val manager: SidekickConnectionManager,
    private val interval: Duration = 1.seconds,
) {
    fun events(serial: String?): Flow<AppConnectionEvent> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            manager.changes.collect { if (serial == null || it.device == serial) send(it) }
        }
        var known = emptySet<ConnectionKey>()
        while (isActive) {
            // A failed look (adb hiccup) keeps what we knew rather than ending every app.
            val running = runCatching { instances(serial).toSet() }.getOrDefault(known)
            running.forEach { key -> runCatching { manager.connect(key) } }
            (known - running).forEach { key -> manager.ended(key) }
            known = running
            delay(interval)
        }
    }
}
