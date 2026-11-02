package io.yamsergey.dta.sidekick.util

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Delayed work on one DTA thread (feature 020: push deadlines, delayed pushes, script timers). */
interface TaskScheduler {
    fun nowMs(): Long

    /** Runs [task] after [delayMs]; the returned function cancels it if it has not run. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** One daemon thread named [name]; a task that throws does not stop the thread. */
class ThreadScheduler(private val name: String) : TaskScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, name).apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }

    override fun nowMs(): Long = System.nanoTime() / 1_000_000

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val future = executor.schedule({
            try {
                task()
            } catch (_: Throwable) {
                // Tasks report their own failures; the thread must survive anything (constitution I).
            }
        }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}
