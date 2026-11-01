package io.yamsergey.dta.sidekick.ui

/**
 * When a window's content is worth summarising (feature 015, research R4): [quietMs] after
 * its last draw, or [maxWaitMs] after the burst of drawing began, whichever comes first.
 * Pure; the recorder feeds it draws and asks it on the main looper.
 */
class Settle(private val quietMs: Long = QUIET_MS, private val maxWaitMs: Long = MAX_WAIT_MS) {
    data class Fired(val byCap: Boolean)

    private var burstStart: Long? = null
    private var lastDraw = 0L

    /** Settles that came from the cap in a row: the window keeps drawing (an animation). */
    var capsInARow = 0
        private set

    fun added(now: Long) {
        burstStart = now
        lastDraw = now
    }

    fun drew(now: Long) {
        if (burstStart == null) burstStart = now
        lastDraw = now
    }

    fun dueAt(): Long? = burstStart?.let { minOf(lastDraw + quietMs, it + maxWaitMs) }

    /** The settle, when it is due at [now]; it then waits for the next draw. */
    fun fire(now: Long): Fired? {
        val start = burstStart ?: return null
        val due = dueAt() ?: return null
        if (now < due) return null
        val byCap = lastDraw + quietMs > start + maxWaitMs
        capsInARow = if (byCap) capsInARow + 1 else 0
        burstStart = null
        return Fired(byCap)
    }

    companion object {
        const val QUIET_MS = 250L
        const val MAX_WAIT_MS = 1000L
    }
}
