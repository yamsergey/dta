package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the apps with DTA on a device connected while someone watches them (feature 009,
 * FR-017): an app that starts is connected (or refused), one whose process went away is
 * ended, a link that died while the app runs is lost and re-dialled. The daemon does the
 * looking so its clients never poll. Events come from the [manager], the only owner of
 * connection state.
 *
 * D-074: one scan loop for all watchers. Each watcher ran its own (adb devices + cat /proc/net/unix
 * every [interval] per client); now each watched device is scanned once per [interval], whoever
 * watches it, and the loop stops with the last watcher.
 */
class AppConnectionWatcher(
    /** Running apps with a sidekick on [serial], or on every device when null. */
    private val instances: (serial: String?) -> List<ConnectionKey>,
    private val manager: SidekickConnectionManager,
    private val interval: Duration = 1.seconds,
    /** D-074: the socket id discovery last saw for an app (a new one is a new process). */
    private val socketOf: (ConnectionKey) -> String? = { null },
    /** D-143: the package in front on [serial] (a change of it is told as a `foreground` event); null: not known. */
    private val foreground: (serial: String) -> String? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** What each watcher watches: a device, or every device ([ALL]). */
    private val watching = ConcurrentHashMap<Any, String>()
    private var loop: Job? = null

    /** D-143: the package in front per device as the last look saw it, and the changes of it (told to every watcher of that device). */
    private val lastForeground = ConcurrentHashMap<String, String>()
    private val foregroundChanges = kotlinx.coroutines.flow.MutableSharedFlow<AppConnectionEvent>(extraBufferCapacity = 64)

    fun events(serial: String?): Flow<AppConnectionEvent> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            manager.changes.collect { if (serial == null || it.device == serial) send(it) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            foregroundChanges.collect { if (serial == null || it.device == serial) send(it) }
        }
        // Connected before this watcher came: no change will say so.
        manager.connectedKeys().filter { serial == null || it.serial == serial }.forEach { send(AppConnectionEvent(it.serial, it.packageName, "connected")) }
        val token = Any()
        join(token, serial ?: ALL)
        try {
            awaitCancellation()
        } finally {
            leave(token)
        }
    }

    @Synchronized
    private fun join(token: Any, target: String) {
        watching[token] = target
        if (loop?.isActive != true) loop = scope.launch { scan() }
    }

    @Synchronized
    private fun leave(token: Any) {
        watching.remove(token)
        if (watching.isEmpty()) {
            loop?.cancel()
            loop = null
            lastForeground.clear()
        }
    }

    private suspend fun scan() = kotlinx.coroutines.coroutineScope {
        var known = emptySet<ConnectionKey>()
        // D-069: each app is dialled on its own, at most one dial per app at a time. Dialled one after another,
        // an app whose sidekick does not answer (a frozen background process: a 10 s hello wait) held the whole
        // round, so a freshly launched app behind a few of them was not dialled within `dta run`'s 30 s.
        val dialling = ConcurrentHashMap.newKeySet<ConnectionKey>()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val targets = watching.values.toSet().let { t -> if (ALL in t) listOf<String?>(null) else t.toList() }
            val running = HashSet<ConnectionKey>()
            val looked = HashSet<String?>()
            val devices = LinkedHashSet<String>()
            for (target in targets) {
                // A failed look (adb hiccup) keeps what we knew rather than ending every app.
                val found = runCatching { instances(target) }.getOrElse { known.filter { k -> target == null || k.serial == target } }
                running += found
                looked += target
                if (target != null) devices += target
                found.forEach { devices += it.serial }
            }
            running.filter { dialling.add(it) }.forEach { key ->
                launch {
                    try {
                        runCatching { manager.connect(key, background = true, socket = socketOf(key)) }
                    } finally {
                        dialling.remove(key)
                    }
                }
            }
            // D-143: the package in front, once per device and round: an app brought to the front without a new process changes no
            // connection, so the clients are told here (the first look says nothing: they read the apps when they start).
            for (serial in devices) {
                val now = runCatching { foreground(serial) }.getOrNull() ?: continue
                val before = lastForeground.put(serial, now)
                if (before != null && before != now) foregroundChanges.tryEmit(AppConnectionEvent(serial, now, "foreground"))
            }
            // Only apps of a device looked at this round can have ended.
            (known - running).filter { k -> null in looked || k.serial in looked }.forEach { key -> manager.ended(key) }
            known = running
            delay(interval)
        }
    }

    private companion object {
        /** Not a serial: every device. */
        const val ALL = "\u0000all"
    }
}
