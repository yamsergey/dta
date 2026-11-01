package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Sources

/**
 * What the main thread is handling right now (feature 016, research R3): a click or back opens
 * a scope (exact), an app's `during` block a declared one. Whatever is recorded inside — a
 * window, a toast, a request issued, an app event — is caused by it. An exit hook does not run
 * when the handler throws, so a scope older than [STALE_MS] is dropped and counted: a stale
 * scope must never link later events (constitution III). Other threads have no scope.
 */
class HandlingScope(
    private val isMain: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() },
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private class Entry(val cause: CauseLink, val kind: String, val at: Long)

    private val stack = ArrayList<Entry>()
    private val costs = ArrayDeque<Long>()

    var staleDropped = 0
        private set

    /** Opens an exact scope for the input event [eventId] of [kind] (e.g. `input/click`). */
    fun enter(eventId: String, kind: String) =
        push(CauseLink(eventId, Basis.EXACT, CauseRules.issuedDuring(kind)), kind)

    /** Opens a scope the app declared for its event [eventId]. */
    fun enterDeclared(eventId: String) =
        push(CauseLink(eventId, Basis.DECLARED, CauseRules.APP_SCOPE, declaredBy = Sources.APP), Sources.APP)

    fun exit() {
        if (!isMain()) return
        val e = stack.removeLastOrNull() ?: return
        record(clock() - e.at)
    }

    /** The innermost live scope's cause, or null outside any (and on other threads). */
    fun current(): CauseLink? {
        if (!isMain()) return null
        dropStale()
        return stack.lastOrNull()?.cause
    }

    /** The kind of the innermost live scope (`input/back` while a back is handled). */
    fun currentKind(): String? {
        if (!isMain()) return null
        dropStale()
        return stack.lastOrNull()?.kind
    }

    /** (scopes measured, median ms, max ms). */
    @Synchronized
    fun costs(): Triple<Int, Long, Long> {
        val sorted = costs.sorted()
        return Triple(sorted.size, sorted.getOrElse(sorted.size / 2) { 0 }, sorted.lastOrNull() ?: 0)
    }

    private fun push(cause: CauseLink, kind: String) {
        if (!isMain()) return
        dropStale()
        stack += Entry(cause, kind, clock())
    }

    private fun dropStale() {
        val now = clock()
        val live = stack.filter { now - it.at <= STALE_MS }
        if (live.size != stack.size) {
            staleDropped += stack.size - live.size
            stack.clear()
            stack += live
        }
    }

    @Synchronized
    private fun record(ms: Long) {
        costs.addLast(ms)
        if (costs.size > KEPT) costs.removeFirst()
    }

    companion object {
        /** A handler running longer than this is already an ANR in the making. */
        const val STALE_MS = 2_000L
        private const val KEPT = 200
    }
}
