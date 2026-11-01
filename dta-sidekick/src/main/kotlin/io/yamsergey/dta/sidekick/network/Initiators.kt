package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.FrameClassifier
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import java.util.Collections
import java.util.WeakHashMap

/**
 * Initiator stacks (FR-009..012). For asynchronous APIs the stack is taken where the app
 * scheduled the work ([remember], e.g. at `Call.enqueue`) and looked up later on the
 * worker thread ([forCall]) — the gap Android Studio leaves, where async requests show
 * only dispatcher frames. Calls are held weakly. Never throws: a failed capture is an
 * initiator that states why.
 */
class Initiators(
    private val stackOf: () -> Array<StackTraceElement> = { Thread.currentThread().stackTrace },
    /** What the app is handling on this thread right now (016): the request's cause. */
    private val scope: () -> io.yamsergey.dta.protocol.timeline.CauseLink? = { io.yamsergey.dta.sidekick.timeline.TimelineRuntime.scopeCause() },
) {
    private val scheduled: MutableMap<Any, Initiator> = Collections.synchronizedMap(WeakHashMap())

    fun capture(site: InitiatorSite): Initiator {
        val thread = Thread.currentThread().name
        val cause = try {
            scope()
        } catch (_: Throwable) {
            null
        }
        return try {
            FrameClassifier.initiator(thread, site, stackOf().asList()).copy(cause = cause)
        } catch (t: Throwable) {
            Initiator(thread = thread, site = site, missing = "stack capture failed: ${t.message ?: t.javaClass.simpleName}", cause = cause)
        }
    }

    fun remember(call: Any, site: InitiatorSite) {
        try {
            scheduled[call] = capture(site)
        } catch (_: Throwable) {
        }
    }

    /** The stack remembered for [call], or the current one with [fallbackSite]. */
    fun forCall(call: Any?, fallbackSite: InitiatorSite): Initiator =
        call?.let { scheduled[it] } ?: capture(fallbackSite)
}
