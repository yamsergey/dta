package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import java.util.WeakHashMap

/**
 * Feature 064 (US4, research R4–R5): which click a coroutine was launched during. A coroutine
 * created while a click is handled on the main thread, or while a tagged coroutine runs on the
 * creating thread, is tagged with that click; its resumes set this thread's cause and its
 * suspensions clear it, so a request it issues on any thread is linked to the click
 * (`launched-during:click`). A tag ends when its coroutine completes or [ttlMs] after it was
 * created, whichever comes first; an ended tag never links (constitution III). [onLive] is told
 * when the first tag goes live (add the resume hooks, synchronously: the coroutine must not
 * resume before) and when the last one ends (remove them). Pure: no Android, no reflection.
 */
class LineageTags(
    private val clock: () -> Long,
    /** The coroutine a frame belongs to (follows `completion` while it is a suspend-function frame). */
    private val rootOf: (Any?) -> Any?,
    /** Whether a coroutine has completed; false when it cannot be told. */
    private val isDone: (Any) -> Boolean,
    private val onLive: (Boolean) -> Unit,
    private val ttlMs: Long = TTL_MS,
) {
    private class Tag(val cause: CauseLink, val at: Long) {
        @Volatile var ended = false
    }

    private val tags = WeakHashMap<Any, Tag>()
    @Volatile private var count = 0
    private val running = ThreadLocal<Tag?>()
    private val liveLock = Any()
    private var hooked = false

    /**
     * A coroutine was created; its continuation is [completion]. [scope] is the main thread's
     * open handling scope (null outside one) of input [kind].
     */
    fun created(completion: Any?, scope: CauseLink?, kind: String?) {
        val cause = scope?.takeIf { it.basis == Basis.EXACT && kind != null }
            ?.let { CauseLink(it.from, Basis.EXACT, CauseRules.launchedDuring(kind!!)) }
            ?: running.get()?.takeIf { !it.ended }?.cause
            ?: return
        val root = rootOf(completion) ?: return
        synchronized(tags) {
            tags[root] = Tag(cause, clock())
            count = tags.size
        }
        syncLive()
    }

    fun resumed(frame: Any?) {
        if (count == 0) {
            if (running.get() != null) running.set(null)
            return
        }
        val root = rootOf(frame)
        running.set(synchronized(tags) { tags[root] })
    }

    @Suppress("UNUSED_PARAMETER")
    fun suspended(frame: Any?) {
        if (running.get() != null) running.set(null)
    }

    /** The click whose coroutine runs on this thread now, or null. */
    fun current(): CauseLink? = running.get()?.takeIf { !it.ended }?.cause

    fun live(): Int = count

    /** Ends tags whose coroutine completed or whose time is up; tells [onLive] when none is left. */
    fun sweep() {
        if (count == 0) return
        val now = clock()
        synchronized(tags) {
            val it = tags.entries.iterator()
            while (it.hasNext()) {
                val (root, tag) = it.next()
                if (now - tag.at >= ttlMs || isDone(root)) {
                    tag.ended = true
                    it.remove()
                }
            }
            count = tags.size
        }
        syncLive()
    }

    private fun syncLive() {
        synchronized(liveLock) {
            val want = count > 0
            if (want != hooked) {
                hooked = want
                onLive(want)
            }
        }
    }

    companion object {
        const val TTL_MS = 10_000L
    }
}
