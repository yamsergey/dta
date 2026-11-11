package io.yamsergey.dta.present.layout

/**
 * Feature 057 (US2): when the Layout view takes its next capture, and the line that says why.
 * Auto follows the app's screen-change signal: one capture after [DEBOUNCE_MS] of quiet, at
 * least [gapMs] apart (doubled up to [MAX_GAP_MS] while captures are slow on the app's main
 * thread), [STEADY_GAP_MS] apart while the screen never settles; one capture at a time; only
 * while shown. Manual never captures by itself; it says when the screen changed after the shown
 * capture. Pure: the caller tells it what happened and asks [due].
 */
class RefreshPolicy {
    enum class Mode { AUTO, MANUAL }

    enum class Signal { WAITING, WATCHING, UNSUPPORTED, RECONNECTING, NO_DTA }

    var mode = Mode.MANUAL
    var shown = false
    /** A window that is not the app's is in front: Auto waits for the app (US4). */
    var inFront: String? = null
    var signal = Signal.WAITING

    private var inFlight = false
    private var capturedAt: Long? = null
    private var startedAt = Long.MIN_VALUE / 2
    private var firstChange: Long? = null
    private var lastChange: Long? = null
    private var reasons = linkedSetOf<String>()
    private var lastMainMs: Long? = null

    /** The current minimum time between captures. */
    var gapMs = MIN_GAP_MS
        private set

    /** Another device or app: nothing of the old one counts. */
    fun reset() {
        inFlight = false; capturedAt = null; startedAt = Long.MIN_VALUE / 2
        firstChange = null; lastChange = null; reasons.clear(); lastMainMs = null
        gapMs = MIN_GAP_MS; signal = Signal.WAITING
    }

    fun changed(at: Long, why: List<String>) {
        if (capturedAt != null && at < capturedAt!!) return
        if (firstChange == null) firstChange = at
        lastChange = at
        reasons += why
    }

    fun captureStarted(at: Long) { inFlight = true; startedAt = at }

    fun captureDone(at: Long, mainThreadMs: Long?) {
        inFlight = false
        capturedAt = at
        firstChange = null
        lastChange = null
        reasons.clear()
        lastMainMs = mainThreadMs
        gapMs = if (mainThreadMs != null && mainThreadMs > SLOW_MS) (gapMs * 2).coerceAtMost(MAX_GAP_MS) else MIN_GAP_MS
    }

    fun captureFailed() { inFlight = false }

    /** The screen keeps changing: changes have not paused for [STEADY_MS]. */
    fun keepsChanging(now: Long): Boolean {
        val first = firstChange ?: return false
        val last = lastChange ?: return false
        return now - first >= STEADY_MS && now - last < DEBOUNCE_MS
    }

    /** When to capture next (epoch ms), or null when nothing is due. */
    fun due(now: Long): Long? {
        if (mode != Mode.AUTO || !shown || inFlight || inFront != null) return null
        val first = firstChange ?: return null
        val last = lastChange ?: return null
        val gap = if (keepsChanging(now)) maxOf(gapMs, STEADY_GAP_MS) else gapMs
        val settled = minOf(last + DEBOUNCE_MS, first + STEADY_MS)
        return maxOf(settled, startedAt + gap)
    }

    /** The line under the Refresh choice, and whether it is a caveat. */
    fun line(now: Long): Pair<String, Boolean> {
        val cost = "Each capture briefly runs on the app's main thread."
        val cannot = when (signal) {
            Signal.NO_DTA -> "it has no DTA inside, so it reports no screen changes"
            Signal.UNSUPPORTED -> "its DTA is older and reports no screen changes"
            else -> null
        }
        val changedSince = firstChange?.let { f -> capturedAt?.let { c -> "The screen changed ${((f - c) / 1000).coerceAtLeast(0)} s after this capture (${reasons.joinToString(", ")})." } }
        return when (mode) {
            Mode.MANUAL -> if (changedSince != null) "$changedSince Refresh to see it. Manual, because each capture briefly runs on the app's main thread." to true
            else "Refresh by hand. $cost" to false
            Mode.AUTO -> when {
                cannot != null -> "Auto cannot follow this app: $cannot. Refresh to update." to true
                inFront != null -> "Paused while $inFront is in front of the app; refreshes when the app is back." to true
                signal == Signal.RECONNECTING -> "Auto is waiting for the app to be reachable again." to true
                keepsChanging(now) -> "The screen keeps changing: Auto refreshes every ${maxOf(gapMs, STEADY_GAP_MS) / 1000} s. Choose Manual to stop it." to true
                gapMs > MIN_GAP_MS -> "Captures are slow (${lastMainMs} ms on the app's main thread): Auto waits ${gapMs / 1000.0} s between them." to true
                else -> "Follows the app: refreshes after the screen changes (windows, layout, Compose state), at most once a second, while this tab is shown. $cost" to false
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 500L
        const val MIN_GAP_MS = 1_000L
        const val MAX_GAP_MS = 8_000L
        const val SLOW_MS = 100L
        const val STEADY_MS = 3_000L
        const val STEADY_GAP_MS = 3_000L
    }
}
