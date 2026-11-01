package io.yamsergey.dta.daemon.ui

import io.yamsergey.dta.daemon.layout.WindowDump
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.layout.ScreenWindowKind
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Windows that are not the app's — permission prompts, system dialogs, the keyboard, other
 * apps on top — recorded by the host from the device's window list (feature 015 US5, research
 * R11). Sampled every [intervalMs] only while someone reads the journal ([touch] within
 * [LEASE_MS]); the first sample of a lease is the baseline. Windows shorter than the interval
 * may be missed, which the capability states. The app's own toasts come from the sidekick; the
 * wallpaper shows behind any translucent window and is not an event.
 */
class HostWindowWatch(
    private val pkg: String,
    private val journal: io.yamsergey.dta.daemon.timeline.Timeline,
    private val dumpsys: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    val intervalMs: Long = INTERVAL_MS,
) {
    private class Seen(val ref: WindowRef, val id: String, val at: Long)

    @Volatile
    private var leaseUntil = 0L
    private var previous: Map<String, Seen>? = null

    /** A reader read the journal: keep sampling for a while. */
    fun touch() {
        leaseUntil = clock() + LEASE_MS
    }

    fun leased(): Boolean = clock() < leaseUntil

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(intervalMs)
            runCatching { sampleOnce() }
        }
    }

    suspend fun sampleOnce() {
        if (!leased()) {
            previous = null
            return
        }
        val windows = WindowDump.screenWindows(WindowDump.parse(dumpsys()), pkg)
            // A window left behind by the app's dead process no longer names its owner.
            .filter { !it.app && !it.name.startsWith("$pkg/") && it.kind != ScreenWindowKind.TOAST && it.type != "WALLPAPER" }
            .associateBy { "${it.owner}|${it.name}" }
        val before = previous
        val now = clock()
        if (before == null) {
            previous = windows.mapValues { (_, w) -> Seen(ref(w), "", now) }
            return
        }
        val next = HashMap<String, Seen>()
        for ((key, w) in windows) {
            val seen = before[key]
            next[key] = seen ?: ref(w).let { r -> Seen(r, journal.hostWindow(appeared = true, r, now, null, null), now) }
        }
        for ((key, seen) in before) {
            if (key !in windows) journal.hostWindow(appeared = false, seen.ref, now, now - seen.at, seen.id.ifEmpty { null })
        }
        previous = next
    }

    private fun ref(w: ScreenWindow) = WindowRef(
        windowId = w.name,
        kind = kindOf(w.kind),
        title = if ('/' in w.name) w.name.substringAfterLast('.') else w.name,
        owner = w.owner,
        secure = w.secure,
        app = false,
    )

    private fun kindOf(kind: ScreenWindowKind) = when (kind) {
        ScreenWindowKind.ACTIVITY -> WindowKind.ACTIVITY
        ScreenWindowKind.DIALOG -> WindowKind.DIALOG
        ScreenWindowKind.POPUP -> WindowKind.POPUP
        ScreenWindowKind.TOAST -> WindowKind.TOAST
        ScreenWindowKind.KEYBOARD -> WindowKind.KEYBOARD
        ScreenWindowKind.SYSTEM -> WindowKind.SYSTEM
        ScreenWindowKind.OTHER_APP -> WindowKind.OTHER_APP
    }

    companion object {
        const val INTERVAL_MS = 500L
        const val LEASE_MS = 60_000L
    }
}
