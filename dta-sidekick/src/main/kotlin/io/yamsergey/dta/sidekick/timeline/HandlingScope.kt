package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Sources

/**
 * What a thread is handling right now (feature 016, research R3): a click or back opens a scope
 * on the main thread (exact), an app's `during` block a declared one on whichever thread runs
 * it. Whatever is recorded inside — a window, a toast, a request issued, an app event — is
 * caused by it. Each thread has its own scopes. An exit hook does not run when the handler
 * throws, so a scope older than [STALE_MS] is dropped and counted: a stale scope must never
 * link later events (constitution III).
 */
class HandlingScope(
    /** Exact scopes (input) open on the main thread only. */
    private val isMain: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() },
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private class Entry(val cause: CauseLink, val kind: String, val at: Long)

    private val stacks = object : ThreadLocal<ArrayList<Entry>>() {
        override fun initialValue() = ArrayList<Entry>()
    }
    /** Touched by the main thread only. */
    private val main = ArrayList<Entry>()
    private val costs = ArrayDeque<Long>()

    var staleDropped = 0
        private set

    /** Opens an exact scope for the input event [eventId] of [kind] (e.g. `input/click`); main thread only. */
    fun enter(eventId: String, kind: String) {
        if (!isMain()) return
        push(main, CauseLink(eventId, Basis.EXACT, CauseRules.issuedDuring(kind)), kind)
    }

    /** Opens a scope the app declared for its event [eventId], on this thread. */
    fun enterDeclared(eventId: String) =
        push(thisStack(), CauseLink(eventId, Basis.DECLARED, CauseRules.APP_SCOPE, declaredBy = Sources.APP), Sources.APP)

    fun exit() {
        val e = thisStack().removeLastOrNull() ?: return
        record(clock() - e.at)
    }

    /** The innermost live scope's cause on this thread, or null outside any. */
    fun current(): CauseLink? = live(thisStack()).lastOrNull()?.cause

    /** The kind of the innermost live scope (`input/back` while a back is handled). */
    fun currentKind(): String? = live(thisStack()).lastOrNull()?.kind

    /** (scopes measured, median ms, max ms). */
    @Synchronized
    fun costs(): Triple<Int, Long, Long> {
        val sorted = costs.sorted()
        return Triple(sorted.size, sorted.getOrElse(sorted.size / 2) { 0 }, sorted.lastOrNull() ?: 0)
    }

    private fun thisStack(): ArrayList<Entry> = if (isMain()) main else stacks.get()!!

    private fun push(stack: ArrayList<Entry>, cause: CauseLink, kind: String) {
        live(stack)
        stack += Entry(cause, kind, clock())
    }

    private fun live(stack: ArrayList<Entry>): List<Entry> {
        val now = clock()
        val alive = stack.filter { now - it.at <= STALE_MS }
        if (alive.size != stack.size) {
            synchronized(this) { staleDropped += stack.size - alive.size }
            stack.clear()
            stack += alive
        }
        return stack
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
