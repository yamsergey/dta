package io.yamsergey.dta.sidekick.runtime

import android.content.Context
import android.content.pm.PackageManager
import io.yamsergey.dta.protocol.data.HeapLimits
import io.yamsergey.dta.protocol.data.HeapRunInfo
import io.yamsergey.dta.protocol.data.HeapWalk
import io.yamsergey.dta.sidekick.hooks.HookAttach
import io.yamsergey.dta.sidekick.hooks.NativeAgent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feature 074: a heap snapshot by class, on request only. The native walk (JVMTI, whole heap, one pass)
 * stops the app's threads for its duration; it runs on a worker thread of its own, never the main thread,
 * and only one at a time. Nothing here runs unless a call asks.
 */
internal class Heap(private val context: Context) {
    private val running = AtomicBoolean(false)

    private val run: String by lazy {
        val start = if (android.os.Build.VERSION.SDK_INT >= 24) android.os.Process.getStartElapsedRealtime() else 0L
        "${android.os.Process.myPid()}-$start"
    }

    private val namespaces: List<String> by lazy {
        runCatching {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            }
            HeapRows.namespaces(
                context.packageName,
                listOf(context.applicationInfo.className) + info.activities.orEmpty().map { it.name },
            )
        }.getOrDefault(listOf(context.packageName))
    }

    fun runInfo(): HeapRunInfo = HeapRunInfo(run, namespaces)

    fun walk(maxPauseMs: Long, gc: Boolean): HeapWalk {
        val bound = maxPauseMs.coerceAtLeast(1)
        fun refused(why: String) = HeapWalk(run = run, refusal = why, maxPauseMs = bound)
        if (!HookAttach.attached) return refused("the heap cannot be walked: ${HookAttach.reason ?: "the DTA agent is not attached"}")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return refused("the heap walk never runs on the main thread")
        if (!running.compareAndSet(false, true)) return refused("a heap walk is already running in the app")
        try {
            if (gc) { java.lang.Runtime.getRuntime().gc(); System.runFinalization() }
            val raw = NativeAgent.nativeHeapHistogram(bound) ?: return refused("the DTA agent is not attached")
            (raw[4] as? String)?.let { return refused("the runtime refused the heap walk: $it") }
            val stats = raw[3] as LongArray
            val pauseMs = (stats[0] + 500) / 1000
            if (stats[3] == 1L) return HeapWalk(run = run, pauseMs = pauseMs, gcBefore = gc, stopped = true, maxPauseMs = bound)
            @Suppress("UNCHECKED_CAST") val classes = raw[0] as Array<Class<*>>
            val counts = raw[1] as LongArray
            val bytes = raw[2] as LongArray
            val appLoader = context.classLoader
            val rows = HeapRows.assemble(
                classes.indices.map { i ->
                    val c = classes[i]
                    val l = c.classLoader
                    HeapRows.Raw(
                        HeapRows.className(c),
                        HeapRows.loaderKind(l?.javaClass?.name, l != null && l === appLoader),
                        l?.let { "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}" },
                        counts[i], bytes[i],
                    )
                },
                namespaces,
            )
            val notes = buildList {
                add("Boot-image and zygote objects are counted: platform rows include objects the app did not allocate.")
                if (!gc) add("Garbage was not collected first: objects that are no longer reachable may be counted.")
                if (stats[2] > 0) add("${stats[2]} objects of classes loaded during the walk are not counted per class.")
            }
            return HeapWalk(run = run, classes = rows, pauseMs = pauseMs, gcBefore = gc, notes = notes, maxPauseMs = bound)
        } finally {
            running.set(false)
        }
    }

    companion object {
        const val DEFAULT_MAX_PAUSE_MS = HeapLimits.DEFAULT_MAX_PAUSE_MS
    }
}
