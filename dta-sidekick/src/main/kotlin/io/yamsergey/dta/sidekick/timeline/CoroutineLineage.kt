package io.yamsergey.dta.sidekick.timeline

import android.os.SystemClock
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Feature 064 (US4, research R4–R5): links a request to the click whose coroutine issued it. Hooks
 * the empty probes the Kotlin stdlib calls for the coroutine debugger (`DebugProbesKt`): creation
 * is hooked while capture is on; resume and suspension only while a tag is live (they run on every
 * resume), added synchronously on the first tagged creation, taken out [LINGER_MS] after the last
 * tag ended. Never throws into the app; what does not install is [failure].
 */
class CoroutineLineage(private val scope: HandlingScope) {
    @Volatile var failure: String? = null
        private set

    @Volatile private var tags: LineageTags? = null
    private var probes: Class<*>? = null
    private var base: Class<*>? = null
    private var baseName: String? = null
    private var completion: Field? = null
    private var job: Class<*>? = null
    private var isCompleted: Method? = null
    @Volatile private var hooked = false
    @Volatile private var offSince = 0L

    private val resumed = object : EntryHandler {
        override fun onEntry(self: Any?, params: Array<Any?>) { tags?.resumed(params.getOrNull(0)) }
    }
    private val suspended = object : EntryHandler {
        override fun onEntry(self: Any?, params: Array<Any?>) { tags?.suspended(params.getOrNull(0)) }
    }

    fun install(loader: ClassLoader?) {
        try {
            probes = Class.forName(PROBES, false, loader)
            base = Class.forName(BASE, false, loader).also { baseName = it.name }
            completion = base!!.getDeclaredField("completion").apply { isAccessible = true }
            job = runCatching { Class.forName(JOB, false, loader) }.getOrNull()
            isCompleted = job?.getMethod("isCompleted")
        } catch (t: Throwable) {
            failure = "requests from coroutines are not linked to the tap that launched them: this app's Kotlin has no coroutine probes (${t.javaClass.simpleName})"
            return
        }
        val t = LineageTags(SystemClock::uptimeMillis, ::rootOf, ::isDone, ::live, inResume = ::inResume)
        tags = t
        val created = Hooks.entry(probes!!, "probeCoroutineCreated", CREATED, object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<Any?>) {
                t.created(params.getOrNull(0), scope.current(), scope.currentKind())
            }
        })
        if (created !is Hooks.Install.Installed) {
            tags = null
            failure = "requests from coroutines are not linked to the tap that launched them: ${created.describe()}"
            return
        }
        Thread({
            while (true) {
                try {
                    Thread.sleep(SWEEP_MS)
                    t.sweep()
                    if (hooked && offSince != 0L && SystemClock.uptimeMillis() - offSince >= LINGER_MS) unhook()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                }
            }
        }, "dta-lineage").apply { isDaemon = true }.start()
    }

    /**
     * The click whose coroutine runs on this thread, when [stack] is inside a coroutine's resume:
     * a thread that ran a tagged coroutine to its end keeps no cause for its next, unrelated work.
     */
    fun cause(stack: List<StackTraceElement>): CauseLink? {
        val c = tags?.current() ?: return null
        val name = baseName ?: return null
        return c.takeIf { stack.any { it.className == name && it.methodName == "resumeWith" } }
    }

    private fun inResume(): Boolean {
        val name = baseName ?: return false
        return Thread.currentThread().stackTrace.any { it.className == name && it.methodName == "resumeWith" }
    }

    /** Called by [LineageTags] on the thread that made the first tag (before its coroutine resumes). */
    @Synchronized
    private fun live(on: Boolean) {
        if (!on) {
            offSince = SystemClock.uptimeMillis()
            return
        }
        offSince = 0L
        if (hooked) return
        // Both in one class reload (each reload holds this thread).
        val (r, s) = Hooks.together(probes!!, Hooks.Spec.entry("probeCoroutineResumed", PROBE, resumed), Hooks.Spec.entry("probeCoroutineSuspended", PROBE, suspended))
        if (r is Hooks.Install.Installed && s is Hooks.Install.Installed) {
            hooked = true
        } else {
            failure = "requests from coroutines are not linked to the tap that launched them: ${listOf(r, s).first { it !is Hooks.Install.Installed }.describe()}"
            Hooks.remove(probes!!, "probeCoroutineResumed", PROBE, resumed)
            Hooks.remove(probes!!, "probeCoroutineSuspended", PROBE, suspended)
        }
    }

    @Synchronized
    private fun unhook() {
        if (!hooked || offSince == 0L) return
        Hooks.remove(probes!!, "probeCoroutineResumed", PROBE, resumed)
        Hooks.remove(probes!!, "probeCoroutineSuspended", PROBE, suspended)
        hooked = false
        offSince = 0L
    }

    private fun rootOf(frame: Any?): Any? {
        var f = frame
        var hops = 0
        val b = base ?: return f
        while (f != null && b.isInstance(f) && hops++ < MAX_HOPS) f = completion!!.get(f) ?: return f
        return f
    }

    private fun isDone(root: Any): Boolean =
        try {
            job?.isInstance(root) == true && isCompleted!!.invoke(root) == true
        } catch (_: Throwable) {
            false
        }

    companion object {
        private const val PROBES = "kotlin.coroutines.jvm.internal.DebugProbesKt"
        private const val BASE = "kotlin.coroutines.jvm.internal.BaseContinuationImpl"
        private const val JOB = "kotlinx.coroutines.Job"
        private const val CREATED = "(Lkotlin/coroutines/Continuation;)Lkotlin/coroutines/Continuation;"
        private const val PROBE = "(Lkotlin/coroutines/Continuation;)V"
        private const val SWEEP_MS = 500L
        /** A burst of clicks pays for adding the resume hooks once (research R5: about 3 ms). */
        const val LINGER_MS = 5_000L
        private const val MAX_HOPS = 256
    }
}
